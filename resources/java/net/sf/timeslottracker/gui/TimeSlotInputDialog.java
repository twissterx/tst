package net.sf.timeslottracker.gui;

import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.HeadlessException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Calendar;
import java.util.Date;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

import net.sf.timeslottracker.data.Task;
import net.sf.timeslottracker.gui.taskmodel.TaskModel;
import net.sf.timeslottracker.gui.taskmodel.TaskModelFactory;
import net.sf.timeslottracker.gui.taskmodel.TaskValue;
import net.sf.timeslottracker.utils.SwingUtils;

/**
 * TimeSlot create dialog
 * <p>
 * It contains input field for description with some last descriptions from
 * active task (history)
 * <p>
 * Also contains task selection. After updated description history.
 * 
 * @version File version: $Revision: 1118 $, $Date: 2009-08-04 19:26:06 +0700
 *          (Tue, 04 Aug 2009) $
 * @author Last change: $Author: cnitsa $
 */
@SuppressWarnings("serial")
public class TimeSlotInputDialog extends AbstractSimplePanelDialog {

  private final AbstractAction ACTION_CANCEL = new AbstractAction() {
    public void actionPerformed(ActionEvent e) {
      wasCancel = true;
      close();
    }
  };

  private final ActionListener ACTION_NEW_ISSUE_TASK = new AbstractAction() {
    public void actionPerformed(ActionEvent e) {
      changeActiveTask();

      layoutManager.getTasksInterface().addTaskFromIssueTracker();
      updateTaskModel();

      selectActiveTask();
    }
  };

  private final ActionListener ACTION_NEW_TASK = new AbstractAction() {
    public void actionPerformed(ActionEvent e) {
      changeActiveTask();

      layoutManager.getTasksInterface().add(null, null);
      updateTaskModel();

      selectActiveTask();
    }
  };

  private final AbstractAction ACTION_OK = new AbstractAction("OK") {
    public void actionPerformed(ActionEvent e) {
      wasCancel = false;
      changeActiveTask();
      close();
    }
  };

  private DescriptionInputComboBox inputComboBox;

  private final LayoutManager layoutManager;

  private TaskModel taskmodel;

  private boolean wasCancel;

  private DatetimeEditPanel startDate;

  public TimeSlotInputDialog(LayoutManager layoutManager)
      throws HeadlessException {
    super(layoutManager, layoutManager
        .getCoreString("timing.start.input.title"));

    this.layoutManager = layoutManager;

    initActions();
  }

  private void initActions() {
    // connect cancelAction with ESC key
    getRootPane().registerKeyboardAction(ACTION_CANCEL,
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        JComponent.WHEN_IN_FOCUSED_WINDOW);

    // connect okAction with Enter key
    getRootPane().registerKeyboardAction(ACTION_OK,
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
        JComponent.WHEN_IN_FOCUSED_WINDOW);

    // connect new task with ins key
    getRootPane().registerKeyboardAction(ACTION_NEW_TASK,
        KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0),
        JComponent.WHEN_IN_FOCUSED_WINDOW);

    // connect new issue task with ins + shift key
    getRootPane().registerKeyboardAction(ACTION_NEW_ISSUE_TASK,
        KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.ALT_MASK),
        JComponent.WHEN_IN_FOCUSED_WINDOW);

    addWindowListener(new WindowAdapter() {
      public void windowClosing(WindowEvent e) {
        ACTION_CANCEL.actionPerformed(new ActionEvent(this, 1, ""));
      }
    });
  }

  /**
   * @return timeslot description, may be null
   */
  public String getDescription() {
    return wasCancel ? null : inputComboBox.getDescription();
  }

  public Task getSelectedTask() {
    TaskValue selectedItem = (TaskValue) taskmodel.getSelectedItem();
    Task selectedTask = layoutManager.getTimeSlotTracker().getDataSource()
        .getTask(selectedItem.getId());
    return selectedTask;
  }

  private void changeActiveTask() {
    Task task = getSelectedTask();
    if (task != null) {
      layoutManager.getTasksInterface().selectTask(task);
    }
  }

  private void close() {
    SwingUtils.saveLocation(this);
    dispose();
  }

  protected JPanel createButtons() {
    FlowLayout layout = new FlowLayout();
    layout.setHgap(15);

    JButton saveButton = new JButton(
        layoutManager.getCoreString("timing.start.input.button.save"),
        layoutManager.getIcon("save"));
    saveButton.addActionListener(ACTION_OK);
    getRootPane().setDefaultButton(saveButton);

    JButton cancelButton = new JButton(
        layoutManager.getCoreString("timing.start.input.button.cancel"),
        layoutManager.getIcon("cancel"));
    cancelButton.addActionListener(ACTION_CANCEL);

    JPanel buttonsPanel = new JPanel(layout);
    buttonsPanel.add(cancelButton);
    buttonsPanel.add(saveButton);

    return buttonsPanel;
  }

  @Override
  protected DialogPanel getDialogPanel() {
    return new DialogPanel(GridBagConstraints.HORIZONTAL, 0.0);
  }

  @Override
  protected void fillDialogPanel(DialogPanel panel) {
    // manipulation with task
    taskmodel = createTaskModel();
    selectActiveTask();
    Task selectedTask = getSelectedTask();

    // adding description
    inputComboBox = new DescriptionInputComboBox(layoutManager, false,
        selectedTask);
    panel.addRow(layoutManager.getCoreString("timing.start.input.prompt"),
        inputComboBox);

    // adding user selected start time
    boolean readonly = true;
    startDate = new DatetimeEditPanel(layoutManager, readonly, true, true);
    startDate.setDatetime(Calendar.getInstance().getTime());
    panel.addRow(coreString("editDialog.timeslot.start.date.name") + ":",
        startDate);

    // adding task
    JComboBox taskComboBox = new JComboBox(taskmodel);
    panel.addRow(layoutManager.getCoreString("timing.start.input.prompt.task"),
        taskComboBox);
    taskComboBox.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent e) {
        inputComboBox.setActiveTask(getSelectedTask(), false);
      }
    });

    panel.addRow(layoutManager.getCoreString("timing.start.input.information"));
  }

  @Override
  protected void beforeShow() {
    pack();
    setResizable(true);
  }

  @Override
  protected int getDefaultHeight() {
    return (int) getSize().getHeight();
  }

  @Override
  protected int getDefaultWidth() {
    return 450;
  }

  private TaskModel createTaskModel() {
    return new TaskModelFactory(layoutManager).createTaskModel();
  }

  private void selectActiveTask() {
    Task actualTask = layoutManager.getTimeSlotsInterface().getSelectedTask();
    if (actualTask != null) {
      taskmodel.setSelectedItem(new TaskValue(actualTask));
    }
  }

  private void updateTaskModel() {
    inputComboBox.setModel(createTaskModel());
  }

  /**
   * @return Date - user selected timeslot start date, may be null
   */
  public Date getStartDate() {
    return startDate.getDatetime();
  }
}
