package net.sf.timeslottracker.idledetector;

import net.sf.timeslottracker.data.AttributeType;
import net.sf.timeslottracker.data.CheckBoxAttribute;

/**
 * Attribute type for considering idle time
 * 
 * @author twister
 *
 */
public class NoIdleAttributeType extends AttributeType {

	private static NoIdleAttributeType INSTANCE = new NoIdleAttributeType();
	
	private static final String NAME = "NO-IDLE-TIME";
	
	private NoIdleAttributeType() {
		super(new CheckBoxAttribute());

	    setName(NAME);
	    setDescription("Register time when user is away");
	    setDefault("");
	    setUsedInTasks(true);
	    setBuiltin(true);
	}

	public static NoIdleAttributeType getInstance() {
		return INSTANCE;
	}
}
