/*
 * Copyright (C) 2012 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.clouddrive.ecms.filters;

import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.ecm.webui.component.explorer.UIJCRExplorer;
import org.exoplatform.ecm.webui.component.explorer.UIJcrExplorerContainer;
import org.exoplatform.webui.application.WebuiRequestContext;
import org.exoplatform.webui.core.UIApplication;
import org.exoplatform.webui.ext.filter.UIExtensionFilter;
import org.exoplatform.webui.ext.filter.UIExtensionFilterType;

import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Filter for cloud files.
 */
public abstract class AbstractCloudDriveNodeFilter implements UIExtensionFilter {

  protected List<String> providers;

  public AbstractCloudDriveNodeFilter() {
    super();
  }

  public AbstractCloudDriveNodeFilter(List<String> providers) {
    super();
    this.providers = providers;
  }

  /**
   * {@inheritDoc}
   */
  public boolean accept(Map<String, Object> context) throws Exception {
    if (context == null) {
      return true;
    } else {
      boolean accepted = false;
      Node contextNode = (Node) context.get(Node.class.getName());
      if (contextNode == null) {
        UIJCRExplorer uiExplorer = (UIJCRExplorer) context.get(UIJCRExplorer.class.getName());
        if (uiExplorer != null) {
          contextNode = uiExplorer.getCurrentNode();
        }

        if (contextNode == null) {
          WebuiRequestContext reqContext = WebuiRequestContext.getCurrentInstance();
          UIApplication uiApp = reqContext.getUIApplication();
          UIJcrExplorerContainer jcrExplorerContainer = uiApp.getChild(UIJcrExplorerContainer.class);
          if (jcrExplorerContainer != null) {
            UIJCRExplorer jcrExplorer = jcrExplorerContainer.getChild(UIJCRExplorer.class);
            contextNode = jcrExplorer.getCurrentNode();
          }
        }
      }

      if (contextNode != null) {
        accepted = accept(contextNode);
      }
      return accepted;
    }
  }

  /**
   * {@inheritDoc}
   */
  public UIExtensionFilterType getType() {
    return UIExtensionFilterType.MANDATORY;
  }

  /**
   * {@inheritDoc}
   */
  public void onDeny(Map<String, Object> context) throws Exception {
  }

  // ****************** internals ******************

  protected boolean acceptProvider(CloudProvider provider) {
    if (providers != null && providers.size() > 0) {
      boolean accepted = providers.contains(provider.getId());
      if (accepted) {
        return true;
      } else {
        // TODO compare by class inheritance
        return false;
      }
    } else {
      return true;
    }
  }

  protected abstract boolean accept(Node node) throws RepositoryException;
}
