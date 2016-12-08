package net.sf.timeslottracker.gui.layouts.classic.tasks;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.util.Date;
import java.util.GregorianCalendar;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import net.sf.timeslottracker.core.Action;
import net.sf.timeslottracker.core.ActionListener;
import net.sf.timeslottracker.core.Configuration;
import net.sf.timeslottracker.core.TimeSlotTracker;
import net.sf.timeslottracker.core.TimeoutTimer;
import net.sf.timeslottracker.data.Task;
import net.sf.timeslottracker.data.TaskChangedListener;
import net.sf.timeslottracker.data.TimeSlotChangedListener;
import net.sf.timeslottracker.gui.DialogPanel;
import net.sf.timeslottracker.gui.LayoutManager;
import net.sf.timeslottracker.gui.TaskInfoInterface;
import net.sf.timeslottracker.gui.TimeSlotFilterListener;
import net.sf.timeslottracker.gui.attributes.AttributesPanel;
import net.sf.timeslottracker.gui.attributes.AttributesPanel.AttributesPanelListener;
import net.sf.timeslottracker.gui.dateperiod.DatePeriod;
import net.sf.timeslottracker.gui.listeners.TaskSelectionChangeListener;
import net.sf.timeslottracker.utils.StringUtils;

/**
 * A module for timeslottracker to present selected task data.
 * 
 * @version File version: $Revision: 1099 $, $Date: 2009-05-16 08:53:21 +0700
 *          (Sat, 16 May 2009) $
 * @author Last change: $Author: cnitsa $
 */
@SuppressWarnings("serial")
public class TaskInfo extends JTabbedPane implements TaskInfoInterface {

  private class TimeSlotFilterAction implements TimeSlotFilterListener {

    @Override
    public void actionPerformed(Action action) {
      TaskInfo.this.data = (DatePeriod) action.getParam();
      updateTimes();
    }

  }

  static final public String ACTION_UPDATE_TIMERS = "taskInfo.timePanel.updateTimers";

  public LayoutManager layoutManager;

  private final TimeSlotTracker timeSlotTracker;

  private final GregorianCalendar calendar;

  /** dialog panel where we will keep all our info **/
  private final DialogPanel dialogPanel;

  /** a reference to actually showed task */
  private Task actualTask;

  private final JLabel taskName = new JLabel();

  private final JLabel taskDescription = new JLabel();

  private final TaskInfoTimePanel timeThisTask;

  private final TaskInfoTimePanel timeIncludingSubtasks;

  private final JPanel infoPanel;

  private final AttributesPanel attributesPanel;

  private DatePeriod data;

  /**
   * Constructs a new task info panel
   */
  public TaskInfo(LayoutManager layoutManager) {
    // super(new BorderLayout());
    this.layoutManager = layoutManager;
    this.timeSlotTracker = layoutManager.getTimeSlotTracker();
    this.calendar = new GregorianCalendar();

    infoPanel = new JPanel(new BorderLayout());
    attributesPanel = new AttributesPanel(layoutManager, (Task) null, true);

    // constructs dialog panel with our task's info variables
    dialogPanel = new DialogPanel(GridBagConstraints.BOTH, 0.0);
    // dialogPanel.addTitle(layoutManager.getString("taskinfo.title"));
    JLabel labelName = new JLabel(layoutManager.getString("taskinfo.taskName"));
    Font fontUsed = labelName.getFont();
    Font plainFont = fontUsed.deriveFont(Font.PLAIN);
    labelName.setFont(plainFont);
    JLabel labelDescription = new JLabel(
        layoutManager.getString("taskinfo.taskDescription"));
    labelDescription.setFont(plainFont);
    JLabel labelTimeThisTask = new JLabel(
        layoutManager.getString("taskinfo.time.thisTask"));
    labelTimeThisTask.setFont(plainFont);
    JLabel labelTimeIncludingSubtasks = new JLabel(
        layoutManager.getString("taskinfo.time.includingSubtasks"));
    labelTimeIncludingSubtasks.setFont(plainFont);
    timeThisTask = new TaskInfoTimePanel(layoutManager, false);
    timeIncludingSubtasks = new TaskInfoTimePanel(layoutManager, true);

    dialogPanel.addRow(labelName, taskName);
    dialogPanel.addRow(labelDescription, taskDescription);
    dialogPanel.addRow(labelTimeThisTask, timeThisTask);
    dialogPanel.addRow(labelTimeIncludingSubtasks, timeIncludingSubtasks);
    infoPanel.add(dialogPanel, BorderLayout.CENTER);
    addTab(layoutManager.getString("taskinfo.tab.info.title"), infoPanel);
    addTab(attributesPanel.getAttributePanelTitle(), attributesPanel);
    attributesPanel.setAttributesPanelListener(new AttributesPanelListener() {

      @Override
      public void handleUpdate() {
        setTitleAt(1, attributesPanel.getAttributePanelTitle());
      }
    });

    // create a timeout listener to update times every X seconds
    installTimeoutListener();
    timeSlotTracker.addActionListener(new TaskChangedAction());
    layoutManager.addActionListener(new TaskSelectionChangeAction());
    layoutManager.addActionListener(new TimeSlotChangeAction());
    layoutManager.addActionListener(new TimeSlotFilterAction());
  }

  private void installTimeoutListener() {
    Configuration configuration = timeSlotTracker.getConfiguration();
    int updateTimeout = configuration.getInteger(
        Configuration.TASKINFO_REFRESH_TIMEOUT, 30).intValue();
    Object[] updateArgs = { new Integer(updateTimeout) };
    String updateName = layoutManager.getString("taskinfo.timer.update.name",
        updateArgs);
    TimeUpdater timeUpdater = new TimeUpdater();
    timeSlotTracker.addActionListener(timeUpdater, ACTION_UPDATE_TIMERS);
    TimeoutTimer updateTimer = new TimeoutTimer(timeSlotTracker, updateName,
        timeUpdater, updateTimeout, -1);
  }

  public void show(Task task) {
    actualTask = task;
    taskName.setText(task == null ? StringUtils.EMPTY : task.getName());
    taskDescription
        .setText(task == null || task.getDescription() == null ? StringUtils.EMPTY
            : task.getDescription().replace('\n', ' '));
    refresh();
  }

  public void refresh() {
    updateTimes();
    updateAttributes();
  }

  private void updateTimes() {
    if (actualTask == null) {
      timeThisTask.clear();
      timeIncludingSubtasks.clear();
    } else {
      timeThisTask.setTimes(getTimes(false));
      timeIncludingSubtasks.setTimes(getTimes(true));
    }
  }

  private void updateAttributes() {
    if (actualTask != null) {

      attributesPanel.setTask(actualTask);
      attributesPanel.reloadFields();
    }
  }

  private String[] getTimes(boolean includeSubtasks) {
    String[] times = new String[4];
    Date selectedDay = null;
    Date selectedWeek = null;
    Date selectedMonth = null;
    GregorianCalendar aktDay = new GregorianCalendar(
        timeSlotTracker.getLocale());
    if (includeSubtasks) {
      selectedDay = timeIncludingSubtasks.getSelectedDay();
      selectedWeek = timeIncludingSubtasks.getSelectedWeek();
      selectedMonth = timeIncludingSubtasks.getSelectedMonth();
    } else {
      selectedDay = timeThisTask.getSelectedDay();
      selectedWeek = timeThisTask.getSelectedWeek();
      selectedMonth = timeThisTask.getSelectedMonth();
    }
    if (selectedDay != null) {
      aktDay.setTime(selectedDay);
    }
    aktDay.set(GregorianCalendar.HOUR_OF_DAY, 0);
    aktDay.set(GregorianCalendar.MINUTE, 0);
    aktDay.set(GregorianCalendar.SECOND, 0);
    aktDay.set(GregorianCalendar.MILLISECOND, 0);

    Date startDate = aktDay.getTime();
    calendar.setTime(startDate);
    calendar.add(GregorianCalendar.DAY_OF_MONTH, 1);
    Date stopDate = calendar.getTime();

    times[0] = layoutManager.formatDuration(actualTask.getTime(includeSubtasks,
        getStartDate(null), getStopDate(null))); // null - means all time
    times[1] = layoutManager.formatDuration(actualTask.getTime(includeSubtasks,
        getStartDate(startDate), getStopDate(stopDate)));

    if (selectedWeek != null) {
      aktDay.setTime(selectedWeek);
    }
    calendar.setTime(aktDay.getTime());

    // Adjust for custom first day of week
    Configuration configuration = timeSlotTracker.getConfiguration();
    int diff = aktDay.get(GregorianCalendar.DAY_OF_WEEK)
        - configuration.getInteger(Configuration.WEEK_FIRST_DAY,
            aktDay.getFirstDayOfWeek());

    diff = (diff + 7) % 7;
    calendar.add(GregorianCalendar.DAY_OF_MONTH, -diff);
    startDate = calendar.getTime();
    calendar.add(GregorianCalendar.DAY_OF_MONTH, 7);
    stopDate = calendar.getTime();
    times[2] = layoutManager.formatDuration(actualTask.getTime(includeSubtasks,
        getStartDate(startDate), getStopDate(stopDate)));

    if (selectedMonth != null) {
      aktDay.setTime(selectedMonth);
    }
    calendar.setTime(aktDay.getTime());
    calendar.set(GregorianCalendar.DAY_OF_MONTH, 1);
    startDate = calendar.getTime();
    calendar.add(GregorianCalendar.MONTH, 1);
    stopDate = calendar.getTime();
    times[3] = layoutManager.formatDuration(actualTask.getTime(includeSubtasks,
        getStartDate(startDate), getStopDate(stopDate)));

    return times;
  }

  private Date getStopDate(Date stopDate) {
    if (data == null || data.isNoFiltering()) {
      return stopDate;
    }

    Date timeSlotFilterEnd = data.getEndPeriod();
    if (stopDate == null || stopDate.before(timeSlotFilterEnd)) {
      return stopDate;
    }
    return timeSlotFilterEnd;
  }

  private Date getStartDate(Date startDate) {
    if (data == null || data.isNoFiltering()) {
      return startDate;
    }

    Date timeSlotFilterStart = data.getStartPeriod();
    if (startDate == null || timeSlotFilterStart.after(startDate)) {
      return timeSlotFilterStart;
    }

    return startDate;
  }

  private class TimeUpdater implements ActionListener {
    public void actionPerformed(Action action) {
      if (actualTask != null) {
        updateTimes();
      }
    }
  }

  /**
   * Listener to action fired when a task was changed
   * <p>
   * It should repaint that task
   */
  private class TaskChangedAction implements TaskChangedListener {
    public void actionPerformed(Action action) {
      Task task = (Task) action.getParam();
      show(task);
    }
  }

  /**
   * Listener to action fired when a task was changed
   * <p>
   * It should repaint that node
   */
  private class TaskSelectionChangeAction implements
      TaskSelectionChangeListener {
    public void actionPerformed(Action action) {
      Task task = (Task) action.getParam();
      show(task);
    }
  }

  /**
   * Listener to action fired when a timeslot was changed
   * <p>
   * It should repaint that node
   */
  private class TimeSlotChangeAction implements TimeSlotChangedListener {
    public void actionPerformed(Action action) {
      if (actualTask != null) {
        updateTimes();
      }
    }
  }
}
