package net.sf.timeslottracker.data;

import net.sf.timeslottracker.core.ActionListener;

/**
 * An interface (empty one) we have to implement when we want to act as a
 * listener to action when a task has changed.
 * <p>
 * Changed could be a name, description or just status.
 * 
 * File version: $Revision: 998 $, $Date: 2009-05-16 08:53:21 +0700 (Sat, 16 May
 * 2009) $ Last change: $Author: cnitsa $
 */
public interface TaskChangedListener extends ActionListener {

}
