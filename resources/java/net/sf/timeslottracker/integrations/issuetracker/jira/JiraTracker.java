package net.sf.timeslottracker.integrations.issuetracker.jira;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonWriter;
import javax.json.stream.JsonParser;
import javax.json.stream.JsonParser.Event;
import javax.swing.JOptionPane;

import net.sf.timeslottracker.core.Action;
import net.sf.timeslottracker.core.Configuration;
import net.sf.timeslottracker.core.TimeSlotTracker;
import net.sf.timeslottracker.data.Attribute;
import net.sf.timeslottracker.data.DataLoadedListener;
import net.sf.timeslottracker.data.Task;
import net.sf.timeslottracker.data.TimeSlot;
import net.sf.timeslottracker.data.TimeSlotChangedListener;
import net.sf.timeslottracker.integrations.issuetracker.Issue;
import net.sf.timeslottracker.integrations.issuetracker.IssueHandler;
import net.sf.timeslottracker.integrations.issuetracker.IssueKeyAttributeType;
import net.sf.timeslottracker.integrations.issuetracker.IssueTracker;
import net.sf.timeslottracker.integrations.issuetracker.IssueTrackerException;
import net.sf.timeslottracker.integrations.issuetracker.IssueWorklogIdType;
import net.sf.timeslottracker.integrations.issuetracker.IssueWorklogStatusType;
import net.sf.timeslottracker.utils.StringUtils;

import org.apache.commons.codec.binary.Base64;

/**
 * Implementation of Issue Tracker for Jira
 * 
 * <p>
 * JIRA (R) Issue tracking project management software
 * (http://www.atlassian.com/software/jira)
 * 
 * @version File version: $Revision: 1161 $, $Date: 2009-05-16 09:00:38 +0700
 *          (Sat, 16 May 2009) $
 * @author Last change: $Author: cnitsa $
 */
public class JiraTracker implements IssueTracker {

  public static final String JIRA_VERSION_6 = "6";
  public static final String JIRA_VERSION_310 = "3.10";
  public static final String JIRA_VERSION_3 = "3";
  private static final String JIRA_DEFAULT_VERSION = JIRA_VERSION_6;

  private static final long ROUND_MIN = 5;
  
  private static final Logger LOG = Logger
      .getLogger(JiraTracker.class.getName());

  private static String decodeString(String s) {
    Pattern p = Pattern.compile("&#([\\d]+);");
    Matcher m = p.matcher(s);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      m.appendReplacement(sb,
          new String(Character.toChars(Integer.parseInt(m.group(1)))));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static String prepareKey(String key) {
    if (key == null) {
      return null;
    }

    return key.trim().toUpperCase();
  }

  private final ExecutorService executorService;

  private final IssueKeyAttributeType issueKeyAttributeType;

  private final IssueWorklogStatusType issueWorklogStatusType;

  private final Map<String, JiraIssue> key2Issue = Collections
      .synchronizedMap(new HashMap<String, JiraIssue>());

  private final Pattern patternIssueId = Pattern
      .compile("<key id=\"([0-9]+)\">([\\d,\\s\u0021-\u0451]+)<");

  private final Pattern patternSummary = Pattern
      .compile("<summary>([\\d,\\s\u0021-\u0451]+)<");

  /**
   * JIRA password per application runtime session
   */
  private String sessionPassword = StringUtils.EMPTY;

  private final TimeSlotTracker timeSlotTracker;

  private final String issueUrlTemplate;
  private final String filterUrlTemplate;

  private final String version;

  public JiraTracker(final TimeSlotTracker timeSlotTracker) {
    this.timeSlotTracker = timeSlotTracker;
    this.executorService = Executors.newSingleThreadExecutor();

    this.issueKeyAttributeType = IssueKeyAttributeType.getInstance();
    this.issueWorklogStatusType = IssueWorklogStatusType.getInstance();

    this.issueUrlTemplate = timeSlotTracker.getConfiguration().get(
        Configuration.JIRA_ISSUE_URL_TEMPLATE,
        "{0}/si/jira.issueviews:issue-xml/{1}/?{2}");

    this.version = timeSlotTracker.getConfiguration()
        .get(Configuration.JIRA_VERSION, JIRA_DEFAULT_VERSION);

    this.filterUrlTemplate = timeSlotTracker.getConfiguration().get(
        Configuration.JIRA_FILTER_URL_TEMPLATE,
        "{0}/sr/jira.issueviews:searchrequest-xml/{1}/SearchRequest-{1}.xml?tempMax=1000&{2}");

    this.timeSlotTracker.addActionListener(new DataLoadedListener() {
      @Override
      public void actionPerformed(Action action) {
        init();
      }
    });
  }

  public void update(final TimeSlot timeSlot) throws IssueTrackerException {
    // getting issue key
    final String key = getIssueKey(timeSlot.getTask());
    if (key == null) {
      return;
    }

    LOG.info("Updating jira worklog for issue with key " + key + " ...");

    // analyze the existing worklog status and duration
    final Attribute statusAttribute = getIssueWorkLogDuration(timeSlot);
    if (statusAttribute != null && 
    		Integer.parseInt(String.valueOf(statusAttribute.get())) == getRoundedMinsDuration(timeSlot.getTime())) {
      
        LOG.info("Skipped updating jira worklog for issue with key " + key
            + ". Reason: current timeslot duration already saved in worklog");
        return;
    }

    Runnable searchIssueTask = new Runnable() {
      public void run() {
        Issue issue = null;
        try {
          issue = getIssue(key);
        } catch (IssueTrackerException e2) {
          LOG.info(e2.getMessage());
        }
        if (issue == null) {
          LOG.info("Nothing updated. Not found issue with key " + key);
          return;
        }

        final String issueId = issue.getId();
        Runnable updateWorklogTask = new Runnable() {
          public void run() {
            try {
              updateWorklog(timeSlot, key, issueId, statusAttribute);
            } catch (IOException e) {
              LOG.warning("Error occured while updating jira worklog:"
                  + e.getMessage());
            }
          }
        };
        executorService.execute(updateWorklogTask);
      }

    };
    executorService.execute(searchIssueTask);
  }

  private Attribute getIssueWorkLogDuration(final TimeSlot timeSlot) {
    for (Attribute attribute : timeSlot.getAttributes()) {
      if (attribute.getAttributeType().equals(issueWorklogStatusType)) {
        return attribute;
      }
    }

    return null;
  }

  private String getIssueWorkLogId(final TimeSlot timeSlot) {
    for (Attribute attribute : timeSlot.getAttributes()) {
      if (attribute.getAttributeType().equals(IssueWorklogIdType.getInstance())) {
    	String id = String.valueOf(attribute.get());
        return id != null && !id.trim().isEmpty() ? id : null;
      }
    }

    return null;
  }
  
  public Issue getIssue(String key) throws IssueTrackerException {
    try {
      key = prepareKey(key);

      // cached id for key?
      synchronized (key2Issue) {
        if (key2Issue.containsKey(key)) {
          return key2Issue.get(key);
        }
      }

      // if not
      String urlString = MessageFormat.format(issueUrlTemplate,
          getBaseJiraUrl(), key, "os_authType=basic");
      URL url = new URL(urlString);
      URLConnection connection = url.openConnection();
      String userpass = getLogin() + ":" + getPassword();
      String basicAuth = "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userpass.getBytes());

      connection.setRequestProperty("Authorization", basicAuth);
      try {
        BufferedReader br = new BufferedReader(
            new InputStreamReader(connection.getInputStream()));
        String line = br.readLine();
        String id = null;
        String summary = null;
        while (line != null) {
          line = decodeString(line);
          Matcher matcherId = patternIssueId.matcher(line);
          if (id == null && matcherId.find()) {
            id = matcherId.group(1);
            continue;
          }

          Matcher matcherSummary = patternSummary.matcher(line);
          if (summary == null && matcherSummary.find()) {
            summary = matcherSummary.group(1);
            continue;
          }

          if (id != null && summary != null) {
            JiraIssue jiraIssue = new JiraIssue(key, id, summary);
            synchronized (key2Issue) {
              key2Issue.put(key, jiraIssue);
            }
            return jiraIssue;
          }

          line = br.readLine();
        }
      } finally {
        connection.getInputStream().close();
      }
      return null;
    } catch (FileNotFoundException e) {
      return null;
    } catch (IOException e) {
      throw new IssueTrackerException(e);
    }
  }

  @Override
  public URI getIssueUrl(Task task) throws IssueTrackerException {
    String issueKey = getIssueKey(task);

    if (issueKey == null) {
      throw new IssueTrackerException("Given task \"" + task.getName()
          + "\" is not issue task (i.e. does not has issue key attribute)");
    }

    String uriStr = getBaseJiraUrl() + "/browse/" + issueKey;
    try {
      return new URI(uriStr);
    } catch (URISyntaxException e) {
      throw new IssueTrackerException(
          "Error occured while creating uri: " + uriStr);
    }
  }

  @Override
  public void getFilterIssues(final String filterId, final IssueHandler handler)
      throws IssueTrackerException {
    Runnable command = new Runnable() {

      @Override
      public void run() {
        try {

          // if not
          String urlString = MessageFormat.format(filterUrlTemplate,
              getBaseJiraUrl(), filterId, "os_authType=basic");
          URL url = new URL(urlString);
          URLConnection connection = url.openConnection();
          
          String userpass = getLogin() + ":" + getPassword();
          String basicAuth = "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userpass.getBytes());

          connection.setRequestProperty("Authorization", basicAuth);
          
          try {

            BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream()));

            String line = br.readLine();
            String id = null;
            String key = null;
            String summary = null;
            while (line != null && !handler.stopProcess()) {
              line = decodeString(line);
              Matcher matcherId = patternIssueId.matcher(line);
              if (id == null && matcherId.find()) {
                id = matcherId.group(1);
                key = matcherId.group(2);
                continue;
              }

              Matcher matcherSummary = patternSummary.matcher(line);
              if (summary == null && matcherSummary.find()) {
                summary = matcherSummary.group(1);
                continue;
              }

              if (id != null && summary != null) {
                JiraIssue jiraIssue = new JiraIssue(key, id, summary);
                handler.handle(jiraIssue);
                id = key = summary = null;
              }

              line = br.readLine();
            }
          } finally {
            connection.getInputStream().close();
          }
        } catch (FileNotFoundException e) {
          LOG.throwing("", "", e);
        } catch (IssueTrackerException e) {
          LOG.throwing("", "", e);
        } catch (IOException e) {
          LOG.throwing("", "", e);
        }
      }
    };
    executorService.execute(command);
  }

  public boolean isIssueTask(Task task) {
    return task != null && getIssueKey(task) != null;
  }

  public boolean isValidKey(String key) {
    String preparedKey = prepareKey(key);
    return preparedKey != null && preparedKey.matches("[a-z,A-Z0-9]+-[0-9]+");
  }

  private HttpURLConnection createConnection(String urlPath, String method) throws IOException {
	  URL url = new URL(urlPath);
	  URLConnection connection = url.openConnection();
	  
	  if (connection instanceof HttpURLConnection) {
	      HttpURLConnection httpConnection = (HttpURLConnection) connection;

	      if (version.equals(JIRA_VERSION_6)) {
	        String basicAuth = "Basic " + new String(new Base64()
	            .encode((getLogin() + ":" + getPassword()).getBytes()));
	        httpConnection.setRequestProperty("Authorization", basicAuth);
	      }
	      
	      httpConnection.setRequestMethod(method);
	      httpConnection.setDoInput(true);
	      httpConnection.setDoOutput(true);
	      httpConnection.setUseCaches(false);
	      httpConnection.setRequestProperty("Content-Type",
	          getContentType());
		  
	      return httpConnection;
	  } else {
		  throw new IOException("Null or invalid type of connection.");
	  }
  }
  
  private void deleteWorklog(final TimeSlot timeSlot) throws IOException {
	  String worklogId = getIssueWorkLogId(timeSlot);
	  String key = getIssueKey(timeSlot.getTask());
	  
	  if (worklogId != null && key != null) {
		HttpURLConnection connection = createConnection(getBaseJiraUrl() + getWorklogPath(key, worklogId),
				"DELETE");
	  }
  }
  
  private void updateWorklog(final TimeSlot timeSlot, final String key,
                          final String issueId, Attribute statusAttribute)
      throws IOException {
	String worklogId = getIssueWorkLogId(timeSlot);
    long jiraDuration = getRoundedMinsDuration(timeSlot.getTime());      
    String jiraFormDuration = jiraDuration  + "m";
	
	HttpURLConnection connection = createConnection(getBaseJiraUrl() + getWorklogPath(issueId, worklogId), 
			worklogId != null ? "PUT" : "POST");
	    
	if (version.equals(JIRA_VERSION_6)) {
		try (JsonWriter writer = Json.createWriter(connection.getOutputStream())) {
			JsonObject worklog = buildWorklogJson(jiraFormDuration, timeSlot.getStartDate(), timeSlot.getDescription()); 
			writer.writeObject(worklog);			 
		}
	
		try (InputStream inputStream = connection.getInputStream()) {
			if (worklogId == null) {
				worklogId = getWorklogIdFromResponse(inputStream);
	      
				LOG.finest("jira worklogId: " + worklogId);
				  
				if (worklogId != null) {
			        Attribute worklogIdAttribute = new Attribute(IssueWorklogIdType.getInstance());
			        worklogIdAttribute.set(worklogId);
			        timeSlot.getAttributes().add(worklogIdAttribute);
				}
			}
		}
	} else {
		try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream())) {
			writer.append(getAuthorizedParams()).append(getPair("id", issueId))
	              .append(getPair("comment", URLEncoder.encode(timeSlot.getDescription(), "UTF-8")))
	              .append(getPair("worklogId", ""))
	              .append(getPair("timeLogged", jiraFormDuration))
	              .append(getPair("startDate", URLEncoder.encode(new SimpleDateFormat("dd/MMM/yy KK:mm a")
	                      .format(timeSlot.getStartDate()), "UTF-8")))
	              .append(getPair("adjustEstimate", "auto"))
	              .append(getPair("newEstimate", ""))
	              .append(getPair("commentLevel", ""));
			  
			writer.flush();
		}
		  
		try (InputStream inputStream = connection.getInputStream()) {}
	}
	     
	if (statusAttribute == null) {
	  statusAttribute = new Attribute(issueWorklogStatusType);
	  List<Attribute> list = new ArrayList<Attribute>(
	      timeSlot.getAttributes());
	  list.add(statusAttribute);
	  timeSlot.setAttributes(list);
	}
	
	statusAttribute.set(jiraDuration);
	  
	LOG.info("Updated jira worklog with key: " + key);
  }

  private JsonObject buildWorklogJson(String duration, Date startDate, String description) {
	  JsonObject object = Json.createObjectBuilder().
			  add("timeSpent", duration).
			  add("started", new SimpleDateFormat("yyyy-MM-dd'T'HH:MM:SS.sZ")
			  	.format(startDate)).
              add("comment", description).	
			  build();

	  return object;
  }

  private long getRoundedMinsDuration(long duration) {
	  long minsDuration = duration / 1000 / 60;
	  return (long)Math.ceil(minsDuration / (float)ROUND_MIN) * ROUND_MIN;
  }

  private String getWorklogIdFromResponse(InputStream inputStream) {
	  String id = null;
	  JsonParser parser = Json.createParser(inputStream);	  
	  
	  while (parser.hasNext() && id == null) {
		  Event event = parser.next();
		  
		  if (event == Event.KEY_NAME && "id".equals(parser.getString())) {			  
			  parser.next();
			  id = parser.getString();
		  }
	  }
	  
	  return id;
  }

private String getWorklogPath(String issueId, String worklogId) {
    String path;
    if (version.equals(JIRA_VERSION_3)) {
      path = "/secure/LogWork.jspa";
    }
    else if (version.equals(JIRA_VERSION_310)) {
      path = "/secure/CreateWorklog.jspa";
    }
    else {
      path = "/rest/api/2/issue/" + issueId + "/worklog" + (worklogId != null ? "/" + worklogId : "");
    }
    return path;
  }

  private String getContentType() {
    return version.equals(JIRA_VERSION_6) ? "application/json" : "application/x-www-form-urlencoded";
  }

  private String getAuthorizedParams() {
    return "os_username=" + getLogin() + getPair("os_password", getPassword());
  }

  private String getBaseJiraUrl() {
    String url = this.timeSlotTracker.getConfiguration()
        .getString(Configuration.JIRA_URL, "");

    // truncate symbol / if present
    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }

  private String getIssueKey(Task task) {
    for (Attribute attribute : task.getAttributes()) {
      if (attribute.getAttributeType().equals(issueKeyAttributeType)) {
        return String.valueOf(attribute.get());
      }
    }
    return null;
  }

  private String getLogin() {
    return this.timeSlotTracker.getConfiguration()
        .getString(Configuration.JIRA_LOGIN, "");
  }

  private String getPair(String name, String value) {
    return "&" + name + "=" + value;
  }

  private String getPassword() {
    String password = this.timeSlotTracker.getConfiguration()
        .getString(Configuration.JIRA_PASSWORD, sessionPassword);
    if (!StringUtils.isBlank(password)) {
      return password;
    }

    if (StringUtils.isBlank(sessionPassword)) {
      sessionPassword = JOptionPane
          .showInputDialog(timeSlotTracker.getRootFrame(), timeSlotTracker
              .getString("issueTracker.credentialsInputDialog.password"));
    }

    return sessionPassword;
  }

  private void init() {
    // updates when timeslot changed
    this.timeSlotTracker.getLayoutManager()
        .addActionListener(new TimeSlotChangedListener() {
          public void actionPerformed(Action action) {
            Boolean enabled = timeSlotTracker.getConfiguration()
                .getBoolean(Configuration.JIRA_ENABLED, false);

            if (!enabled) {
              return;
            }

            if (!action.getName().equalsIgnoreCase("TimeSlotChanged")) {
              return;
            }

            // no active timeSlot
            TimeSlot timeSlot = (TimeSlot) action.getParam();
            if (timeSlot == null) {
              return;
            }

            boolean isNullStart = timeSlot.getStartDate() == null;
            boolean isNullStop = timeSlot.getStopDate() == null;

            // paused timeSlot
            if (isNullStart && isNullStop) {
              return;
            }

            // started timeSlot
            if (isNullStop) {
              return;
            }

            // removed timeSlot
            if (timeSlot.getTask() == null) {
              return;
            }

            // stopped or edited task
            try {
              update(timeSlot);
            } catch (IssueTrackerException e) {
              LOG.warning(e.getMessage());
            }
          }
        });
  }

}
