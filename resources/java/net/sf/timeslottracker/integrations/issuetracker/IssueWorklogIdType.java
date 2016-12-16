package net.sf.timeslottracker.integrations.issuetracker;

import net.sf.timeslottracker.data.AttributeType;
import net.sf.timeslottracker.data.SimpleTextAttribute;

public class IssueWorklogIdType extends AttributeType {

	private static IssueWorklogIdType INSTANCE = new IssueWorklogIdType();
	
	private static final String NAME = "ISSUE-WORKLOG-ID";
	
	IssueWorklogIdType() {
	    super(new SimpleTextAttribute());

	    setName(NAME);
	    setDescription("Id of issue's worklog");
	    setDefault("");
	    setUsedInTimeSlots(true);
	    setBuiltin(true);
	}

	public static IssueWorklogIdType getInstance() {
		return INSTANCE;
	}

}
