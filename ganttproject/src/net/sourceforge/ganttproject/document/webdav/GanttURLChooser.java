/*
GanttProject is an opensource project management tool. License: GPL3
Copyright (C) 2003-2012 GanttProject Team

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/

package net.sourceforge.ganttproject.document.webdav;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.MalformedURLException;
import java.net.URL;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SpringLayout;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import net.sourceforge.ganttproject.GPLogger;
import net.sourceforge.ganttproject.IGanttProject;
import net.sourceforge.ganttproject.action.CancelAction;
import net.sourceforge.ganttproject.action.GPAction;
import net.sourceforge.ganttproject.action.OkAction;
import net.sourceforge.ganttproject.document.webdav.WebDavResource.WebDavException;
import net.sourceforge.ganttproject.gui.UIFacade;
import net.sourceforge.ganttproject.gui.UIUtil;
import net.sourceforge.ganttproject.gui.options.OptionsPageBuilder;
import net.sourceforge.ganttproject.gui.options.SpringUtilities;
import net.sourceforge.ganttproject.gui.options.model.BooleanOption;
import net.sourceforge.ganttproject.gui.options.model.ChangeValueEvent;
import net.sourceforge.ganttproject.gui.options.model.ChangeValueListener;
import net.sourceforge.ganttproject.gui.options.model.DefaultBooleanOption;
import net.sourceforge.ganttproject.gui.options.model.DefaultStringOption;
import net.sourceforge.ganttproject.gui.options.model.EnumerationOption;
import net.sourceforge.ganttproject.gui.options.model.IntegerOption;
import net.sourceforge.ganttproject.gui.options.model.ListOption;
import net.sourceforge.ganttproject.gui.options.model.StringOption;
import net.sourceforge.ganttproject.language.GanttLanguage;

import org.jdesktop.swingx.JXHyperlink;
import org.jdesktop.swingx.JXList;

import com.google.common.base.Predicate;
import com.google.common.base.Strings;

/**
 * UI component for WebDAV operarions.
 *
 * @author dbarashev (Dmitry Barashev)
 */
class GanttURLChooser {
  private static final GanttLanguage language = GanttLanguage.getInstance();

  private final StringOption myUsername;

  private final StringOption myPassword;

  private final StringOption myPath;

  private final BooleanOption myLock;

  private final IntegerOption myTimeout;

  private SelectionListener mySelectionListener;

  private final ListOption<WebDavServerDescriptor> myServers;

  private final MiltonResourceFactory myWebDavFactory;

  private final GPAction myReloadAction = new GPAction("fileChooser.reload") {
    @Override
    public void actionPerformed(ActionEvent event) {
      try {
        myWebDavFactory.clearCache();
        myWebDavFactory.setCredentials(myUsername.getValue(), myPassword.getValue());
        WebDavResource resource = myWebDavFactory.createResource(buildUrl());
        if (resource.exists() && resource.isCollection()) {
          tableModel.setCollection(resource);
          return;
        }
        WebDavResource parent = resource.getParent();
        if (parent.exists() && parent.isCollection()) {
          tableModel.setCollection(parent);
          return;
        }
      } catch (WebDavException e) {
        showError(e);
      }
    }
  };

  private final GPAction myUpAction = new GPAction("fileChooser.up") {
    @Override
    public void actionPerformed(ActionEvent event) {
      try {
        WebDavResource collection = tableModel.getCollection();
        WebDavResource parent = collection.getParent();
        if (parent != null && parent.exists() && parent.isCollection()) {
          tableModel.setCollection(parent);
          myPath.setValue(new URL(parent.getUrl()).getPath());
        }
      } catch (WebDavException e) {
        showError(e);
      } catch (MalformedURLException e) {
        showError(e);
      }
    }
  };

  private final GPAction myLockAction = new GPAction("fileChooser.lock") {
    @Override
    public void actionPerformed(ActionEvent evt) {
      WebDavResource resource = getSelectedResource();
      if (resource == null) {
        return;
      }
      try {
        if (!resource.isLocked()) {
          resource.lock(myTimeout.getValue());
        }
      } catch (WebDavException e) {
        showError(e);
      }
    }
  };

  private final GPAction myUnlockAction = new GPAction("fileChooser.unlock") {
    @Override
    public void actionPerformed(ActionEvent evt) {
      WebDavResource resource = getSelectedResource();
      if (resource == null) {
        return;
      }
      try {
        if (resource.isLocked()) {
          resource.unlock();
        }
      } catch (WebDavException e) {
        showError(e);
      }
    }
  };

  private final GPAction myDeleteAction = new GPAction("fileChooser.delete") {
    @Override
    public void actionPerformed(ActionEvent evt) {
      final WebDavResource resource = getSelectedResource();
      if (resource == null) {
        return;
      }
      myUiFacade.showOptionDialog(JOptionPane.QUESTION_MESSAGE, GanttLanguage.getInstance().getText("fileChooser.delete.question"), new Action[] {
        new OkAction() {
          @Override
          public void actionPerformed(ActionEvent evt) {
            try {
              resource.delete();
            } catch (WebDavException e) {
              showError(e);
            }
          }
        }, CancelAction.EMPTY
      });
    }
  };

  private final FilesTableModel tableModel = new FilesTableModel();

  private final BooleanOption myReleaseLockOption;

  private JXList table;

  private JButton myLockButton;

  private final UIFacade myUiFacade;

  private final IGanttProject myProject;

  static interface SelectionListener {
    public void setSelection(WebDavResource resource);
  }

  GanttURLChooser(IGanttProject project, UIFacade uiFacade, ListOption<WebDavServerDescriptor> servers, WebDavUri currentUri, StringOption username,
      String password, IntegerOption lockTimeoutOption, BooleanOption releaseLockOption,
      MiltonResourceFactory webDavFactory) {
    myProject = project;
    myUiFacade = uiFacade;
    myWebDavFactory = webDavFactory;
    myPath = new DefaultStringOption("path");
    myServers = servers;
    myUsername = username;
    myPassword = new DefaultStringOption("password", password);
    myPassword.setScreened(true);
    myLock = new DefaultBooleanOption("lock", true);
    myTimeout = lockTimeoutOption;
    myReleaseLockOption = releaseLockOption;

    myServers.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        myPath.setValue("");
        WebDavServerDescriptor server = myServers.getValue();
        myUsername.setValue(server.username);
        myPassword.setValue(server.password);
        System.err.println("password=" + server.password);
      }
    });
    myPath.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        String value = (String) event.getNewValue();
        if (value == null) {
          return;
        }
        String lcValue = value.toLowerCase();
        if (lcValue.startsWith("http://") || lcValue.startsWith("https://")) {
          if (!tryApplyUrl(new WebDavUri(value))) {
            myUpAction.setEnabled(value.split("/").length > 1);
          }
        }
      }
    });
    myUsername.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        myServers.getValue().username = myUsername.getValue();
      }
    });
    myPassword.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        myServers.getValue().password = myPassword.getValue();
      }
    });
    tryApplyUrl(currentUri);
  }

  private boolean tryApplyUrl(WebDavUri currentUri) {
    WebDavServerDescriptor savedServer = findSavedServer(currentUri.buildRootUrl());
    if (savedServer != null && !savedServer.equals(myServers.getValue())) {
      myServers.setValue(savedServer);
    } else {
      WebDavServerDescriptor server = new WebDavServerDescriptor(currentUri.hostName, currentUri.buildRootUrl(), "");
      myServers.addValue(server);
      myServers.setValue(server);
    }
    myPath.setValue(currentUri.path);
    return true;
  }
  private WebDavServerDescriptor findSavedServer(String domainUrl) {
    for (WebDavServerDescriptor server : myServers.getValues()) {
      if (server.rootUrl.equals(domainUrl)) {
        return server;
      }
    }
    return null;
  }

  public JComponent createOpenDocumentUi() {
    return createComponent();
  }

  public JComponent createSaveDocumentUi() {
    return createComponent();
  }

  private WebDavUri buildUrl() {
    WebDavServerDescriptor server= myServers.getValue();
    String host = server.rootUrl;
    if (host.endsWith("/")) {
      host = host.substring(0, host.length() - 1);
    }
    String path = myPath.getValue();
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    return new WebDavUri(server.name, host, path);
  }

  private WebDavResource getSelectedResource() {
    return (WebDavResource) table.getSelectedValue();
  }

  private JComponent createComponent() {
    OptionsPageBuilder builder = new OptionsPageBuilder();

    JPanel panel = new JPanel(new SpringLayout());
    panel.add(new JLabel(language.getCorrectedLabel("webServer")));
    EnumerationOption serverChoiceOption = myServers.asEnumerationOption();
    panel.add(builder.createOptionComponent(null, serverChoiceOption));
    panel.add(new JLabel());
    panel.add(createUsernamePasswordPanel());
    //Box serverBox = Box.createHorizontalBox();
    //serverBox.add(new ServerListEditor(myServers).getComponent());
    panel.add(new JLabel(language.getCorrectedLabel("fileFromServer")));
    panel.add(builder.createOptionComponent(null, myPath));
    //panel.add(serverBox);
//    panel.add(new JLabel(language.getText("userName")));
//    panel.add(builder.createOptionComponent(null, myUsername));
//    panel.add(new JLabel(language.getText("password")));
//    panel.add(builder.createOptionComponent(null, myPassword));

    addEmptyRow(panel);

    {
      JPanel filesTablePanel = new JPanel(new BorderLayout());
      JButton refreshButton = new JButton(myReloadAction);
      JButton upButton = new JButton(myUpAction);
      myLockButton = new JButton(myLockAction);

      panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(myUpAction.getKeyStroke(), myUpAction);
      panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(myReloadAction.getKeyStroke(), myReloadAction);
      panel.getActionMap().put(myUpAction, myUpAction);
      panel.getActionMap().put(myReloadAction, myReloadAction);
      upButton.setText("");
      refreshButton.setText("");
      Box filesHeaderBox = Box.createVerticalBox();
      //filesHeaderBox.add();
      //filesHeaderBox.add(Box.createVerticalStrut(5));
      filesHeaderBox.add(upButton);
      filesHeaderBox.add(Box.createVerticalStrut(5));
      filesHeaderBox.add(refreshButton);
      filesHeaderBox.add(Box.createVerticalGlue());
      filesHeaderBox.add(new JButton(myDeleteAction));
      filesHeaderBox.add(Box.createVerticalStrut(5));
      filesHeaderBox.add(myLockButton);

      filesTablePanel.add(filesHeaderBox, BorderLayout.EAST);
      filesHeaderBox.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 0));
      //panel.add(filesActionsPanel);
      table = new JXList(tableModel);
      table.setHighlighters(UIUtil.ZEBRA_HIGHLIGHTER);
      table.setCellRenderer(new FilesCellRenderer());
      table.addListSelectionListener(new ListSelectionListener() {
        @Override
        public void valueChanged(ListSelectionEvent e) {
          WebDavResource resource = getSelectedResource();
          if (resource == null) {
            return;
          }
          onSelectionChanged(resource);
        }
      });
      table.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          if (e.getClickCount() == 2) {
            tryEnterCollection(table, tableModel);
          }
        }
      });
      table.addKeyListener(new KeyAdapter() {
        @Override
        public void keyReleased(KeyEvent e) {
          if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            tryEnterCollection(table, tableModel);
          }
        }
      });
      filesTablePanel.add(new JScrollPane(table), BorderLayout.CENTER);
      panel.add(new JLabel(language.getText("fileChooser.fileList")));
      panel.add(filesTablePanel);
    }

    addEmptyRow(panel);
//    panel.add(new JLabel(language.getText("webdav.lockResource.label")));
//    panel.add(lockComponent);
//    panel.add(new JLabel(language.getText("webdav.lockTimeout.label")));
//    final JComponent timeoutComponent = (JComponent) builder.createOptionComponent(null, myTimeout);
//    panel.add(timeoutComponent);
//
//    panel.add(new JLabel(language.getText("webdav.lockRelease.label")));
//    final JComponent lockReleaseComponent = (JComponent) builder.createOptionComponent(null, myReleaseLockOption);
//    panel.add(lockReleaseComponent);
//
//    myLock.addChangeValueListener(new ChangeValueListener() {
//      @Override
//      public void changeValue(ChangeValueEvent event) {
//        timeoutComponent.setEnabled(myLock.isChecked());
//        lockReleaseComponent.setEnabled(myLock.isChecked());
//      }
//    });
    SpringUtilities.makeCompactGrid(panel, 6, 2, 0, 0, 10, 5);

    JPanel properties = new JPanel(new BorderLayout());
    properties.add(panel, BorderLayout.NORTH);
    properties.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 0));

    return properties;
  }

  private Component createUsernamePasswordPanel() {
    JPanel grid = new JPanel(new GridLayout(3, 2));
    grid.add(new JLabel(language.getText("userName")));
    final JLabel username = new JLabel(myUsername.getValue());
    myUsername.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        username.setText(myUsername.getValue() == null ? "" : myUsername.getValue());
      }
    });
    grid.add(username);
    grid.add(new JLabel(language.getText("password")));
    final JLabel password = new JLabel(myPassword.getValue() == null ? "" : Strings.repeat("*", myPassword.getValue().length()));
    myPassword.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        password.setText(myPassword.getValue() == null ? "" : Strings.repeat("*", myPassword.getValue().length()));
      }
    });
    grid.add(password);
    grid.add(new JXHyperlink(new GPAction("webdav.configure") {
      @Override
      public void actionPerformed(ActionEvent arg0) {
        WebDavOptionPageProvider optionPage = new WebDavOptionPageProvider();
        optionPage.init(myProject, myUiFacade);
        myUiFacade.createDialog(optionPage.buildPageComponent(), new Action[] {CancelAction.CLOSE}, "").show();
      }
    }));
    UIUtil.walkComponentTree(grid, new Predicate<JComponent>() {
      @Override
      public boolean apply(JComponent input) {
        input.setFont(input.getFont().deriveFont(input.getFont().getSize()*0.82f));
        return true;
      }
    });

    Box result = Box.createHorizontalBox();
    result.add(grid);
    result.add(Box.createHorizontalGlue());
    return grid;
  }

  protected void onSelectionChanged(WebDavResource resource) {
    if (mySelectionListener != null) {
      mySelectionListener.setSelection(resource);
    }
    myPath.setValue(resource.getWebDavUri().path);
    try {
      if (resource.isLocked()) {
        myLockButton.setAction(myUnlockAction);
      } else {
        myLockButton.setAction(myLockAction);
      }
    } catch (WebDavException e) {
      GPLogger.logToLogger(e);
    }
  }

  protected void tryEnterCollection(JXList table, FilesTableModel tableModel) {
    WebDavResource resource = (WebDavResource) table.getSelectedValue();
    try {
      if (resource.isCollection()) {
        tableModel.setCollection(resource);
      }
    } catch (WebDavException e) {
      showError(e);
    }
  }

  private static void addEmptyRow(JPanel form) {
    form.add(Box.createRigidArea(new Dimension(1, 10)));
    form.add(Box.createRigidArea(new Dimension(1, 10)));
  }

  WebDavUri getUrl() {
    return buildUrl();
  }

  String getUsername() {
    return myUsername.getValue();
  }

  String getPassword() {
    return myPassword.getValue();
  }

  int getLockTimeout() {
    return myTimeout.getValue();
  }

  void setSelectionListener(SelectionListener selectionListener) {
    mySelectionListener = selectionListener;
  }

  StringOption getPathOption() {
    return myPath;
  }
  void showError(Exception e) {
    GPLogger.log(e);
  }
}