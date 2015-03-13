/*
 * Copyright (C) 2003-2014 eXo Platform SAS.
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
package org.exoplatform.clouddrive;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Cloud Drive local storage level operations: access internal data and modification.<br>
 * 
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: CloudDriveStorage.java 00000 Dec 16, 2014 pnedonosko $
 * 
 */
public interface CloudDriveStorage {

  /**
   * Tell if given node is a local node in this cloud drive, thus it is not a cloud file, it is not ignored
   * local node and it is not currently updating by the drive (not uploading to remote site).
   * 
   * @param node {@link Node}
   * @return boolean <code>true</code> if node is local only in this drive, <code>false</code> otherwise
   * @throws RepositoryException
   * @throws NotCloudDriveException if node doesn't belong to cloud drive
   * @throws DriveRemovedException if drive removed
   * @throws NotCloudFileException if node is a root folder of the drive
   */
  public boolean isLocal(Node node) throws RepositoryException,
                                   NotCloudDriveException,
                                   NotCloudFileException,
                                   DriveRemovedException;

  /**
   * Tell if given node is ignored in this cloud drive.
   * 
   * @param node {@link Node}
   * @return boolean <code>true</code> if node is ignored, <code>false</code> otherwise
   * @throws RepositoryException
   * @throws DriveRemovedException if drive removed
   * @throws NotCloudDriveException if given node doesn't belong to cloud drive
   * @throws NotCloudFileException if given node is a root folder of the drive
   */
  boolean isIgnored(Node node) throws RepositoryException, NotCloudDriveException, NotCloudFileException, DriveRemovedException;

  /**
   * Mark given file node as ignored. This operation doesn't remove local or remote file. This operation
   * saves the node to persist its ignored state.
   * 
   * @param node {@link Node}
   * @return boolean <code>true</code> if file was ignored, <code>false</code> if it is already ignored
   * @throws RepositoryException
   * @throws DriveRemovedException if drive removed
   * @throws NotCloudDriveException if node doesn't belong to cloud drive
   * @throws NotCloudFileException if node belongs to cloud drive but not represent a cloud file (not yet
   *           added or ignored node) or node is a root folder of the drive
   */
  boolean ignore(Node node) throws RepositoryException,
                           NotCloudDriveException,
                           NotCloudFileException,
                           DriveRemovedException;

  /**
   * Remove ignorance mark if given node marked as ignored. This operation doesn't remove local or
   * remote file. This operation saves the node to persist its unignored state.
   * 
   * @param node {@link Node}
   * @return boolean <code>true</code> if file was unignored successfully, <code>false</code> if it is already
   *         ignored
   * @throws RepositoryException
   * @throws NotCloudDriveException if node doesn't belong to cloud drive
   * @throws NotCloudFileException if node belongs to cloud drive but not represent a cloud file (not yet
   *           added or ignored node) or node is a root folder of the drive
   * @throws DriveRemovedException if drive removed
   */
  boolean unignore(Node node) throws RepositoryException,
                             NotCloudDriveException,
                             DriveRemovedException,
                             NotCloudFileException;

  /**
   * Initiate cloud file creation from this node. If node already represents a cloud file nothing will happen.
   * This operation will have no effect also if ignorance marker set on given node. Use
   * {@link #unignore(Node)} to reset this marker before calling this method.
   * If it is was not a cloud file node, file creation will run asynchronously in another thread. You may use
   * {@link CloudDriveListener} for informing about file creation results.
   * 
   * @param node {@link Node} a node under cloud drive folder
   * @return boolean, <code>true</code> if file creation initiated successfully, <code>false</code> if node
   *         already represents a cloud file
   * @throws RepositoryException if storage error occured
   * @throws NotCloudDriveException if node doesn't belong to cloud drive folder
   * @throws DriveRemovedException if drive removed
   * @throws NotCloudFileException if given node is a root folder of the drive
   * @throws CloudDriveException if creation preparation failed
   * @see #unignore(Node)
   * @see #isIgnored(Node)
   */
  boolean create(Node node) throws RepositoryException,
                           NotCloudDriveException,
                           DriveRemovedException,
                           NotCloudFileException,
                           CloudDriveException;

}
