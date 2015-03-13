/*
 * Copyright (C) 2003-2012 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.clouddrive.gdrive;

import com.google.api.client.http.InputStreamContent;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChildReference;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.ParentReference;
import com.google.api.services.drive.model.User;

import org.exoplatform.clouddrive.CloudDriveAccessException;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudFile;
import org.exoplatform.clouddrive.CloudFileAPI;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.SyncNotSupportedException;
import org.exoplatform.clouddrive.gdrive.GoogleDriveAPI.ChangesIterator;
import org.exoplatform.clouddrive.gdrive.GoogleDriveAPI.ChildIterator;
import org.exoplatform.clouddrive.gdrive.GoogleDriveConnector.API;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudFile;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.clouddrive.oauth2.UserToken;
import org.exoplatform.clouddrive.oauth2.UserTokenRefreshListener;
import org.exoplatform.clouddrive.utils.ExtendedMimeTypeResolver;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

/**
 * JCR local storage for Google Drive. Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: JCRLocalGoogleDrive.java 00000 Sep 13, 2012 pnedonosko $
 */
public class JCRLocalGoogleDrive extends JCRLocalCloudDrive implements UserTokenRefreshListener {

  /**
   * Connect algorithm for Google Drive.
   */
  protected class Connect extends ConnectCommand {

    /**
     * Google Drive service API.
     */
    protected final GoogleDriveAPI api;

    /**
     * Create connect to Google Drive command.
     * 
     * @throws RepositoryException
     * @throws DriveRemovedException
     */
    protected Connect() throws RepositoryException, DriveRemovedException {
      super();
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void fetchFiles() throws CloudDriveException, RepositoryException {
      About about = api.about();
      // drive id
      String id = about.getRootFolderId();

      fetchChilds(id, rootNode);

      // connect metadata
      setChangeId(about.getLargestChangeId());
    }

    protected void fetchChilds(String fileId, Node parent) throws CloudDriveException, RepositoryException {
      ChildIterator children = api.children(fileId);
      iterators.add(children);

      while (children.hasNext() && !Thread.currentThread().isInterrupted()) {
        ChildReference child = children.next();
        File gf = api.file(child.getId());
        if (!gf.getLabels().getTrashed()) { // skip files in Trash
          // create JCR node here
          boolean isFolder = api.isFolder(gf);

          DateTime createDate = gf.getCreatedDate();
          if (createDate == null) {
            throw new GoogleDriveException("File " + gf.getTitle() + " doesn't have Created Date.");
          }
          Calendar created = api.parseDate(createDate.toStringRfc3339());
          DateTime modifiedDate = gf.getModifiedDate();
          if (modifiedDate == null) {
            throw new GoogleDriveException("File " + gf.getTitle() + " doesn't have Modified Date.");
          }
          Calendar modified = api.parseDate(modifiedDate.toStringRfc3339());

          long size = fileSize(gf);

          // link and editLink
          String link = gf.getAlternateLink();

          // TODO apply multiple owners in cloud file and show them in ECM

          Node fileNode;
          CloudFile file;
          if (isFolder) {
            fileNode = openFolder(gf.getId(), gf.getTitle(), parent);
            initFolder(fileNode,
                       gf.getId(),
                       gf.getTitle(),
                       gf.getMimeType(),
                       link,
                       gf.getOwnerNames().get(0),
                       gf.getLastModifyingUserName(),
                       created,
                       modified);

            // go recursive
            fetchChilds(gf.getId(), fileNode);
            file = new JCRLocalCloudFile(fileNode.getPath(),
                                         gf.getId(),
                                         gf.getTitle(),
                                         gf.getAlternateLink(),
                                         gf.getMimeType(),
                                         gf.getLastModifyingUserName(),
                                         gf.getOwnerNames().get(0),
                                         created,
                                         modified,
                                         fileNode,
                                         false);
          } else {
            fileNode = openFile(gf.getId(), gf.getTitle(), parent);
            initFile(fileNode,
                     gf.getId(),
                     gf.getTitle(),
                     gf.getMimeType(),
                     link,
                     gf.getEmbedLink(),
                     gf.getThumbnailLink(),
                     gf.getOwnerNames().get(0),
                     gf.getLastModifyingUserName(),
                     created,
                     modified,
                     size);
            file = new JCRLocalCloudFile(fileNode.getPath(),
                                         gf.getId(),
                                         gf.getTitle(),
                                         gf.getAlternateLink(),
                                         gf.getAlternateLink(),
                                         gf.getEmbedLink(),
                                         gf.getThumbnailLink(),
                                         gf.getMimeType(),
                                         null, // typeMode not required for GoogleDrive
                                         gf.getLastModifyingUserName(),
                                         gf.getOwnerNames().get(0),
                                         created,
                                         modified,
                                         size,
                                         fileNode,
                                         false);
          }

          changed.add(file);
        }
      }
    }
  }

  /**
   * Sync algorithm for Google Drive.
   */
  protected class Sync extends SyncCommand {

    /**
     * Google Drive service API.
     */
    protected final GoogleDriveAPI api;

    /**
     * Existing files being synchronized with cloud.
     */
    protected final Set<Node>      synced = new HashSet<Node>();

    protected ChangesIterator      changes;

    /**
     * Create command for Google Drive synchronization.
     * 
     * @throws RepositoryException
     * @throws DriveRemovedException
     */
    protected Sync() throws RepositoryException, DriveRemovedException {
      super();
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void syncFiles() throws RepositoryException, CloudDriveException {
      long largestChangeId;

      try {
        About about = api.about();
        largestChangeId = about.getLargestChangeId();
      } catch (CloudDriveAccessException e) {
        if (!isAccessScopeMatch()) {
          throw new RefreshAccessException("Renew access key to Google Drive", e);
        }
        throw e;
      }

      long localChangeId = getChangeId();
      if (largestChangeId == localChangeId) {
        // nothing changed
        return;
      }

      long startChangeId = localChangeId + 1;

      if (LOG.isDebugEnabled()) {
        LOG.debug("Synchronizing changes from " + startChangeId + " to about " + largestChangeId);
      }

      changes = api.changes(startChangeId);
      iterators.add(changes);

      if (changes.hasNext()) {
        readLocalNodes(); // read all local nodes to nodes list
        syncNext(); // process changes
      }

      // update sync metadata, use actual change id from the last iterator
      setChangeId(changes.getLargestChangeId());
    }

    protected void syncNext() throws RepositoryException, CloudDriveException {
      while (changes.hasNext() && !Thread.currentThread().isInterrupted()) {
        Change ch = changes.next();
        File gf = ch.getFile(); // gf will be null for deleted

        String[] parents;

        // if parents empty - file deleted or shared file was removed from user drive (My Drive)
        // if file in Trash - proceed to delete also, inside it should be checked for the same ETag
        if (ch.getDeleted() || (parents = getParents(gf)).length == 0) {
          if (hasRemoved(ch.getFileId())) {
            cleanRemoved(ch.getFileId());
            if (LOG.isDebugEnabled()) {
              LOG.debug(">> Returned file removal " + ch.getFileId());
            }
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug(">> File removal " + ch.getFileId());
            }
          }
          deleteFile(ch.getFileId());
        } else {
          if (gf.getLabels().getTrashed()) {
            if (hasRemoved(gf.getId())) {
              cleanRemoved(gf.getId());
              if (LOG.isDebugEnabled()) {
                LOG.debug(">> Returned file trashing " + gf.getId() + " " + gf.getTitle());
              }
            } else {
              if (LOG.isDebugEnabled()) {
                LOG.debug(">> File trashing " + gf.getId() + " " + gf.getTitle());
              }
              deleteFile(gf.getId());
            }
          } else {
            if (hasUpdated(gf.getId())) {
              cleanUpdated(gf.getId());
              if (LOG.isDebugEnabled()) {
                LOG.debug(">> Returned file update " + gf.getId() + " " + gf.getTitle());
              }
            } else {
              if (LOG.isDebugEnabled()) {
                LOG.debug(">> File update " + gf.getId() + " " + gf.getTitle());
              }
              updateFile(gf, parents);
            }
          }
        }
      }
    }

    /**
     * Remove file's node.
     * 
     * @param fileId {@link String}
     * @throws RepositoryException
     */
    protected void deleteFile(String fileId) throws RepositoryException {
      List<Node> existing = nodes.get(fileId);
      if (existing != null) {
        // remove existing file,
        // also clean the nodes map from the descendants (they can be recorded in delta)
        for (Node en : existing) {
          String enpath = en.getPath();
          for (Iterator<List<Node>> ecnliter = nodes.values().iterator(); ecnliter.hasNext();) {
            List<Node> ecnl = ecnliter.next();
            if (ecnl != existing) {
              for (Iterator<Node> ecniter = ecnl.iterator(); ecniter.hasNext();) {
                Node ecn = ecniter.next();
                if (ecn.getPath().startsWith(enpath)) {
                  ecniter.remove();
                }
              }
              if (ecnl.size() == 0) {
                ecnliter.remove();
              }
            } // else will be removed below
          }
          removed.add(enpath);
          en.remove();
        }
        nodes.remove(fileId);
      }
    }

    /**
     * Create or update file's node.
     * 
     * @param gf {@link File}
     * @param parentIds array of Ids of parents (folders)
     * @throws CloudDriveException
     * @throws IOException
     * @throws RepositoryException
     * @throws InterruptedException
     */
    protected void updateFile(File gf, String[] parentIds) throws CloudDriveException, RepositoryException {
      // using changes only related to current parent:
      // * for deleted checking if node exists and remove it
      // * for others update on its parents
      List<Node> existing = nodes.get(gf.getId());

      boolean isFolder = api.isFolder(gf);

      nextParent: for (String parentFileId : parentIds) {
        List<Node> fileParent = nodes.get(parentFileId);
        if (fileParent == null) {
          // no yet existing locally parent... wait for it
          syncNext();

          fileParent = nodes.get(parentFileId);
          if (fileParent == null) {
            // check if it is not shared file and thus its parent may be outside My Drive (not found locally)
            if (gf.getShared()) {
              // for shared file check its parent owner and if not me, then skip the parent
              File gparent = api.file(parentFileId);
              GoogleUser me = getUser();
              boolean notMine = false;
              List<User> parentOwners = gparent.getOwners();
              if (parentOwners != null) {
                for (User owner : parentOwners) {
                  Object uo = owner.getUnknownKeys().get(GoogleDriveAPI.USER_EMAIL_ADDRESS);
                  if (uo != null && !me.getEmail().equals(uo)) {
                    notMine = true;
                  } else {
                    String userId = owner.getPermissionId();
                    if (!me.getId().equals(userId)) {
                      notMine = true;
                    }
                  }
                }
                if (notMine) {
                  continue nextParent;
                }
              }
            }

            // TODO should we run full sync and restore the drive from the cloud side?
            throw new CloudDriveException("Inconsistent changes: cannot find parent Node for '"
                + gf.getTitle() + "'");
          }
        }

        for (Node fp : fileParent) {
          Node fileNode = null;
          Node localNodeCopy = null;
          if (existing == null) {
            existing = new ArrayList<Node>();
            nodes.put(gf.getId(), existing);
          } else {
            for (Node n : existing) {
              localNodeCopy = n;
              if (n.getParent().isSame(fp)) {
                fileNode = n;
                break;
              }
            }
          }

          if (fileNode == null) {
            // create new Node in local JCR
            if (isFolder) {
              if (localNodeCopy == null) {
                fileNode = openFolder(gf.getId(), gf.getTitle(), fp);
              } else {
                // copy from local copy of the folder to a new parent
                fileNode = copyNode(localNodeCopy, fp);
              }
            } else {
              fileNode = openFile(gf.getId(), gf.getTitle(), fp);
            }

            // add created Node to list of existing
            existing.add(fileNode);
          } else if (!fileAPI.getTitle(fileNode).equals(gf.getTitle())) {
            // file was renamed, rename (move) its Node also
            fileNode = moveFile(gf.getId(), gf.getTitle(), fileNode, fp);
          }

          Calendar created = api.parseDate(gf.getCreatedDate().toStringRfc3339());
          Calendar modified = api.parseDate(gf.getModifiedDate().toStringRfc3339());

          long size = fileSize(gf);

          // TODO apply multiple owners in cloud file and show them in WCM

          CloudFile file;
          if (isFolder) {
            initFolder(fileNode,
                       gf.getId(),
                       gf.getTitle(),
                       gf.getMimeType(),
                       gf.getAlternateLink(),
                       gf.getOwnerNames().get(0),
                       gf.getLastModifyingUserName(),
                       created,
                       modified);
            file = new JCRLocalCloudFile(fileNode.getPath(),
                                         gf.getId(),
                                         gf.getTitle(),
                                         gf.getAlternateLink(),
                                         gf.getMimeType(),
                                         gf.getLastModifyingUserName(),
                                         gf.getOwnerNames().get(0),
                                         created,
                                         modified,
                                         fileNode,
                                         true);
          } else {
            initFile(fileNode,
                     gf.getId(),
                     gf.getTitle(),
                     gf.getMimeType(),
                     gf.getAlternateLink(),
                     gf.getEmbedLink(),
                     gf.getThumbnailLink(),
                     gf.getOwnerNames().get(0),
                     gf.getLastModifyingUserName(),
                     created,
                     modified,
                     size);
            file = new JCRLocalCloudFile(fileNode.getPath(),
                                         gf.getId(),
                                         gf.getTitle(),
                                         gf.getAlternateLink(),
                                         gf.getAlternateLink(),
                                         gf.getEmbedLink(),
                                         gf.getThumbnailLink(),
                                         gf.getMimeType(),
                                         null, // typeMode not required for GoogleDrive
                                         gf.getLastModifyingUserName(),
                                         gf.getOwnerNames().get(0),
                                         created,
                                         modified,
                                         size,
                                         fileNode,
                                         true);
          }

          synced.add(fileNode);
          changed.add(file);
        }
      }

      if (existing != null) {
        // need remove other existing (not listed on delta parents)
        for (Iterator<Node> niter = existing.iterator(); niter.hasNext();) {
          Node n = niter.next();
          if (!synced.contains(n)) {
            removed.add(n.getPath());
            niter.remove();
            n.remove();
          }
        }
      }
    }

    protected String[] getParents(File gfile) {
      if (gfile != null) {
        List<ParentReference> parents = gfile.getParents();
        if (parents != null) {
          String[] parentIds = new String[parents.size()];
          for (int i = 0; i < parents.size(); i++) {
            parentIds[i] = parents.get(i).getId();
          }
          return parentIds;
        }
      }
      return new String[0];
    }
  }

  protected class FileAPI extends AbstractFileAPI {

    /**
     * Google Drive service API.
     */
    protected final GoogleDriveAPI api;

    FileAPI() {
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile createFile(Node fileNode,
                                Calendar created,
                                Calendar modified,
                                String mimeType,
                                InputStream content) throws CloudDriveException, RepositoryException {

      // File's metadata.
      File gf = new File();
      gf.setTitle(getTitle(fileNode));
      gf.setMimeType(mimeType);
      gf.setParents(Arrays.asList(new ParentReference().setId(getParentId(fileNode))));
      gf.setCreatedDate(new DateTime(created.getTime()));
      gf.setModifiedDate(new DateTime(modified.getTime()));

      InputStreamContent fileContent = new InputStreamContent(mimeType, content);

      try {
        gf = api.insert(gf, fileContent);
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }

      modified = api.parseDate(gf.getModifiedDate().toStringRfc3339()); // use actual from Google

      long size = fileSize(gf);

      // link and editLink
      String link = gf.getAlternateLink();

      initFile(fileNode,
               gf.getId(),
               gf.getTitle(),
               gf.getMimeType(),
               link,
               gf.getEmbedLink(),
               gf.getThumbnailLink(),
               gf.getOwnerNames().get(0),
               gf.getLastModifyingUserName(),
               created,
               modified,
               size);

      return new JCRLocalCloudFile(fileNode.getPath(),
                                   gf.getId(),
                                   gf.getTitle(),
                                   link,
                                   link,
                                   gf.getEmbedLink(),
                                   gf.getThumbnailLink(),
                                   gf.getMimeType(),
                                   null,
                                   gf.getLastModifyingUserName(),
                                   gf.getOwnerNames().get(0),
                                   created,
                                   modified,
                                   size,
                                   fileNode,
                                   true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile createFolder(Node folderNode, Calendar created) throws CloudDriveException,
                                                                    RepositoryException {
      // Folder metadata.
      File gf = new File();
      gf.setTitle(getTitle(folderNode));
      gf.setMimeType(GoogleDriveAPI.FOLDER_MIMETYPE);
      gf.setParents(Arrays.asList(new ParentReference().setId(getParentId(folderNode))));
      gf.setCreatedDate(new DateTime(created.getTime()));

      try {
        gf = api.insert(gf);
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }

      // use actual dates from Google
      created = api.parseDate(gf.getCreatedDate().toStringRfc3339());
      Calendar modified = api.parseDate(gf.getModifiedDate().toStringRfc3339());

      initFolder(folderNode,
                 gf.getId(),
                 gf.getTitle(),
                 gf.getMimeType(),
                 gf.getAlternateLink(),
                 gf.getOwnerNames().get(0),
                 gf.getLastModifyingUserName(),
                 created,
                 modified);

      return new JCRLocalCloudFile(folderNode.getPath(),
                                   gf.getId(),
                                   gf.getTitle(),
                                   gf.getAlternateLink(),
                                   gf.getMimeType(),
                                   gf.getLastModifyingUserName(),
                                   gf.getOwnerNames().get(0),
                                   created,
                                   modified,
                                   folderNode,
                                   true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFile(Node fileNode, Calendar modified) throws CloudDriveException,
                                                                 RepositoryException {
      // Update existing file metadata and parent (location).
      File gf = api.file(getId(fileNode));
      gf.setTitle(getTitle(fileNode));
      gf.setModifiedDate(new DateTime(modified.getTime()));

      // merge parents (in case of move replace source on destination)
      gf.setParents(mergeParents(getParentId(fileNode), gf.getParents(), findParents(fileNode)));

      try {
        api.update(gf);

        Calendar created = api.parseDate(gf.getCreatedDate().toStringRfc3339());
        modified = api.parseDate(gf.getModifiedDate().toStringRfc3339());

        long size = fileSize(gf);

        // link and editLink
        String link = gf.getAlternateLink();

        initFile(fileNode,
                 gf.getId(),
                 gf.getTitle(),
                 gf.getMimeType(),
                 link,
                 gf.getEmbedLink(),
                 gf.getThumbnailLink(),
                 gf.getOwnerNames().get(0),
                 gf.getLastModifyingUserName(),
                 created,
                 modified,
                 size);

        return new JCRLocalCloudFile(fileNode.getPath(),
                                     gf.getId(),
                                     gf.getTitle(),
                                     link,
                                     link,
                                     gf.getEmbedLink(),
                                     gf.getThumbnailLink(),
                                     gf.getMimeType(),
                                     null,
                                     gf.getLastModifyingUserName(),
                                     gf.getOwnerNames().get(0),
                                     created,
                                     modified,
                                     size,
                                     fileNode,
                                     true);
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFolder(Node folderNode, Calendar modified) throws CloudDriveException,
                                                                     RepositoryException {
      // Update existing folder metadata and parent (location).
      File gf = api.file(getId(folderNode));
      gf.setTitle(getTitle(folderNode));
      gf.setModifiedDate(new DateTime(modified.getTime()));

      // merge parents (in case of move replace source on destination)
      gf.setParents(mergeParents(getParentId(folderNode), gf.getParents(), findParents(folderNode)));

      try {
        api.update(gf);

        Calendar created = api.parseDate(gf.getCreatedDate().toStringRfc3339());
        modified = api.parseDate(gf.getModifiedDate().toStringRfc3339());

        String link = gf.getAlternateLink();

        initFolder(folderNode,
                   gf.getId(),
                   gf.getTitle(),
                   gf.getMimeType(),
                   link,
                   gf.getOwnerNames().get(0),
                   gf.getLastModifyingUserName(),
                   created,
                   modified);

        return new JCRLocalCloudFile(folderNode.getPath(),
                                     gf.getId(),
                                     gf.getTitle(),
                                     gf.getAlternateLink(),
                                     gf.getMimeType(),
                                     gf.getLastModifyingUserName(),
                                     gf.getOwnerNames().get(0),
                                     created,
                                     modified,
                                     folderNode,
                                     true);
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFileContent(Node fileNode, Calendar modified, String mimeType, InputStream content) throws CloudDriveException,
                                                                                                              RepositoryException {
      // Update existing file content and related metadata.
      File gf = api.file(getId(fileNode));
      gf.setMimeType(mimeType);
      gf.setModifiedDate(new DateTime(modified.getTime()));

      InputStreamContent fileContent = new InputStreamContent(mimeType, content);

      try {
        api.update(gf, fileContent);

        Calendar created = api.parseDate(gf.getCreatedDate().toStringRfc3339());
        modified = api.parseDate(gf.getModifiedDate().toStringRfc3339());

        long size = fileSize(gf);

        // link and editLink
        String link = gf.getAlternateLink();

        initFile(fileNode,
                 gf.getId(),
                 gf.getTitle(),
                 gf.getMimeType(),
                 link,
                 gf.getEmbedLink(),
                 gf.getThumbnailLink(),
                 gf.getOwnerNames().get(0),
                 gf.getLastModifyingUserName(),
                 created,
                 modified,
                 size);

        return new JCRLocalCloudFile(fileNode.getPath(),
                                     gf.getId(),
                                     gf.getTitle(),
                                     link,
                                     link,
                                     gf.getEmbedLink(),
                                     gf.getThumbnailLink(),
                                     gf.getMimeType(),
                                     null,
                                     gf.getLastModifyingUserName(),
                                     gf.getOwnerNames().get(0),
                                     created,
                                     modified,
                                     size,
                                     fileNode,
                                     true);
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile copyFile(Node srcFileNode, Node destFileNode) throws CloudDriveException,
                                                                  RepositoryException {
      File gf = new File(); // new file
      gf.setId(getId(srcFileNode));
      gf.setTitle(getTitle(destFileNode));
      gf.setParents(Arrays.asList(new ParentReference().setId(getParentId(destFileNode))));

      try {
        gf = api.copy(gf);
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }

      // use actual dates from Google
      Calendar created = api.parseDate(gf.getCreatedDate().toStringRfc3339());
      Calendar modified = api.parseDate(gf.getModifiedDate().toStringRfc3339());

      long size = fileSize(gf);

      // link and editLink
      String link = gf.getAlternateLink();

      initFile(destFileNode,
               gf.getId(),
               gf.getTitle(),
               gf.getMimeType(),
               link,
               gf.getEmbedLink(),
               gf.getThumbnailLink(),
               gf.getOwnerNames().get(0),
               gf.getLastModifyingUserName(),
               created,
               modified,
               size);

      return new JCRLocalCloudFile(destFileNode.getPath(),
                                   gf.getId(),
                                   gf.getTitle(),
                                   link,
                                   link,
                                   gf.getEmbedLink(),
                                   gf.getThumbnailLink(),
                                   gf.getMimeType(),
                                   null,
                                   gf.getLastModifyingUserName(),
                                   gf.getOwnerNames().get(0),
                                   created,
                                   modified,
                                   size,
                                   destFileNode,
                                   true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile copyFolder(Node srcFolderNode, Node destFolderNode) throws CloudDriveException,
                                                                        RepositoryException {
      File gf = new File(); // new file
      gf.setId(getId(srcFolderNode));
      gf.setTitle(getTitle(destFolderNode));
      gf.setParents(Arrays.asList(new ParentReference().setId(getParentId(destFolderNode))));

      try {
        gf = api.copy(gf);
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }

      // use actual dates from Google
      Calendar created = api.parseDate(gf.getCreatedDate().toStringRfc3339());
      Calendar modified = api.parseDate(gf.getModifiedDate().toStringRfc3339());

      initFolder(destFolderNode,
                 gf.getId(),
                 gf.getTitle(),
                 gf.getMimeType(),
                 gf.getAlternateLink(),
                 gf.getOwnerNames().get(0),
                 gf.getLastModifyingUserName(),
                 created,
                 modified);

      return new JCRLocalCloudFile(destFolderNode.getPath(),
                                   gf.getId(),
                                   gf.getTitle(),
                                   gf.getAlternateLink(),
                                   gf.getMimeType(),
                                   gf.getLastModifyingUserName(),
                                   gf.getOwnerNames().get(0),
                                   created,
                                   modified,
                                   destFolderNode,
                                   true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeFile(String id) throws CloudDriveException, RepositoryException {
      try {
        api.delete(id);
        return true;
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeFolder(String id) throws CloudDriveException, RepositoryException {
      try {
        api.delete(id);
        return true;
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFile(String id) throws CloudDriveException, RepositoryException {
      try {
        File file = api.trash(id);
        return file.getLabels().getTrashed();
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFolder(String id) throws CloudDriveException, RepositoryException {
      try {
        File file = api.trash(id);
        return file.getLabels().getTrashed();
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile untrashFile(Node fileNode) throws CloudDriveException, RepositoryException {
      try {
        File gf = api.untrash(getId(fileNode));
        if (!gf.getLabels().getTrashed()) {
          Calendar created = api.parseDate(gf.getCreatedDate().toStringRfc3339());
          Calendar modified = api.parseDate(gf.getModifiedDate().toStringRfc3339());

          long size = fileSize(gf);

          // link and editLink
          String link = gf.getAlternateLink();

          initFile(fileNode,
                   gf.getId(),
                   gf.getTitle(),
                   gf.getMimeType(),
                   link,
                   gf.getEmbedLink(),
                   gf.getThumbnailLink(),
                   gf.getOwnerNames().get(0),
                   gf.getLastModifyingUserName(),
                   created,
                   modified,
                   size);

          return new JCRLocalCloudFile(fileNode.getPath(),
                                       gf.getId(),
                                       gf.getTitle(),
                                       link,
                                       link,
                                       gf.getEmbedLink(),
                                       gf.getThumbnailLink(),
                                       gf.getMimeType(),
                                       null,
                                       gf.getLastModifyingUserName(),
                                       gf.getOwnerNames().get(0),
                                       created,
                                       modified,
                                       size,
                                       fileNode,
                                       true);
        } // otherwise file wasn't untrashed
        return null;
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile untrashFolder(Node folderNode) throws CloudDriveException, RepositoryException {
      try {
        File gf = api.untrash(getId(folderNode));
        if (!gf.getLabels().getTrashed()) {
          Calendar created = api.parseDate(gf.getCreatedDate().toStringRfc3339());
          Calendar modified = api.parseDate(gf.getModifiedDate().toStringRfc3339());

          String link = gf.getAlternateLink();

          initFolder(folderNode,
                     gf.getId(),
                     gf.getTitle(),
                     gf.getMimeType(),
                     link,
                     gf.getOwnerNames().get(0),
                     gf.getLastModifyingUserName(),
                     created,
                     modified);

          return new JCRLocalCloudFile(folderNode.getPath(),
                                       gf.getId(),
                                       gf.getTitle(),
                                       link,
                                       gf.getMimeType(),
                                       gf.getLastModifyingUserName(),
                                       gf.getOwnerNames().get(0),
                                       created,
                                       modified,
                                       folderNode,
                                       true);
        } // otherwise folder wasn't untrashed
        return null;
      } catch (CloudDriveAccessException e) {
        checkAccessScope(e);
        throw e;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTrashSupported() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile restore(String id, String path) throws NotFoundException,
                                                    CloudDriveException,
                                                    RepositoryException {
      throw new SyncNotSupportedException("Restore not supported");
    }
  }

  /**
   * Create newly connecting drive.
   * 
   * @param user
   * @param driveNode
   * @param sessionProviders
   * @throws CloudDriveException
   * @throws RepositoryException
   */
  protected JCRLocalGoogleDrive(GoogleUser user,
                                Node driveNode,
                                SessionProviderService sessionProviders,
                                NodeFinder finder,
                                ExtendedMimeTypeResolver mimeTypes) throws CloudDriveException,
      RepositoryException {
    super(user, driveNode, sessionProviders, finder, mimeTypes);
    getUser().api().getToken().addListener(this);
  }

  /**
   * Create drive by loading it from local JCR node.
   * 
   * @param apiBuilder {@link API} API builder
   * @param provider {@link GoogleProvider}
   * @param driveNode {@link Node} root of the drive
   * @param sessionProviders
   * @throws RepositoryException if local storage error
   * @throws CloudDriveException if cannot load tokens stored locally
   * @throws GoogleDriveException if error communicating with Google Drive services
   */
  protected JCRLocalGoogleDrive(API apiBuilder,
                                GoogleProvider provider,
                                Node driveNode,
                                SessionProviderService sessionProviders,
                                NodeFinder finder,
                                ExtendedMimeTypeResolver mimeTypes) throws RepositoryException,
      GoogleDriveException,
      CloudDriveException {
    super(loadUser(apiBuilder, provider, driveNode), driveNode, sessionProviders, finder, mimeTypes);
    getUser().api().getToken().addListener(this);
  }

  /**
   * Load user from the drive Node.
   * 
   * @param apiBuilder {@link API} API builder
   * @param provider {@link GoogleProvider}
   * @param driveNode {@link Node} root of the drive
   * @return {@link GoogleUser}
   * @throws RepositoryException
   * @throws GoogleDriveException
   * @throws CloudDriveException
   */
  protected static GoogleUser loadUser(API apiBuilder, GoogleProvider provider, Node driveNode) throws RepositoryException,
                                                                                               GoogleDriveException,
                                                                                               CloudDriveException {
    String username = driveNode.getProperty("ecd:cloudUserName").getString();
    String email = driveNode.getProperty("ecd:userEmail").getString();
    String userId = driveNode.getProperty("ecd:cloudUserId").getString();
    String accessToken = driveNode.getProperty("gdrive:oauth2AccessToken").getString();
    String refreshToken;
    try {
      refreshToken = driveNode.getProperty("gdrive:oauth2RefreshToken").getString();
    } catch (PathNotFoundException e) {
      refreshToken = null;
    }
    long expirationTime = driveNode.getProperty("gdrive:oauth2TokenExpirationTime").getLong();

    GoogleDriveAPI driveAPI = apiBuilder.load(userId, refreshToken, accessToken, expirationTime).build();

    return new GoogleUser(userId, username, email, provider, driveAPI);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public GoogleUser getUser() {
    return (GoogleUser) user;
  }

  /**
   * {@inheritDoc}
   * 
   */
  @Override
  public void onUserTokenRefresh(UserToken token) throws CloudDriveException {
    try {
      jcrListener.disable();
      Node driveNode = rootNode();
      try {
        driveNode.setProperty("gdrive:oauth2AccessToken", token.getAccessToken());
        driveNode.setProperty("gdrive:oauth2RefreshToken", token.getRefreshToken());
        driveNode.setProperty("gdrive:oauth2TokenExpirationTime", token.getExpirationTime());

        driveNode.save();
      } catch (RepositoryException e) {
        rollback(driveNode);
        throw new CloudDriveException("Error updating access key: " + e.getMessage(), e);
      }
    } catch (DriveRemovedException e) {
      throw new CloudDriveException("Error openning drive node: " + e.getMessage(), e);
    } catch (RepositoryException e) {
      throw new CloudDriveException("Error reading drive node: " + e.getMessage(), e);
    } finally {
      jcrListener.enable();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void refreshAccess() throws GoogleDriveException {
    getUser().api().refreshAccess();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void updateAccess(CloudUser newUser) throws CloudDriveException, RepositoryException {
    getUser().api().updateToken(((GoogleUser) newUser).api().getToken());

    // manage access scopes update
    try {
      Node driveNode = rootNode();

      boolean updateScopes;
      try {
        updateScopes = !GoogleDriveAPI.SCOPES_STRING.equals(driveNode.getProperty("gdrive:scopes")
                                                                     .getString());
      } catch (PathNotFoundException e) {
        updateScopes = true;
      }

      if (updateScopes) {
        jcrListener.disable();
        try {
          driveNode.setProperty("gdrive:scopes", GoogleDriveAPI.SCOPES_STRING);
          driveNode.save();
        } catch (RepositoryException e) {
          rollback(driveNode);
          throw new CloudDriveException("Error updating access scopes: " + e.getMessage(), e);
        } finally {
          jcrListener.enable();
        }
      }
    } catch (DriveRemovedException e) {
      throw new CloudDriveException("Error openning drive node: " + e.getMessage(), e);
    } catch (RepositoryException e) {
      throw new CloudDriveException("Error reading drive node: " + e.getMessage(), e);
    }
  }

  /**
   * Check if currently coded scope match the one used by the drive access tokens.
   * 
   * @throws DriveRemovedException
   */
  protected boolean isAccessScopeMatch() throws RepositoryException, DriveRemovedException {
    Node driveNode = rootNode();
    try {
      return GoogleDriveAPI.SCOPES_STRING.equals(driveNode.getProperty("gdrive:scopes").getString());
    } catch (PathNotFoundException e) {
      return false;
    }
  }

  /**
   * Throw {@link RefreshAccessException} if currently coded scope doesn't match the one used by the drive
   * access tokens.
   * 
   * @param cause {@link CloudDriveAccessException}
   * @return <code>false</code> if access coded and currently used access scopes match, otherwise
   *         {@link RefreshAccessException} will be thrown with given cause {@link CloudDriveAccessException}
   * @throws RepositoryException
   * @throws RefreshAccessException
   * @throws DriveRemovedException
   */
  protected boolean checkAccessScope(CloudDriveAccessException cause) throws RepositoryException,
                                                                     RefreshAccessException,
                                                                     DriveRemovedException {
    if (cause != null && !isAccessScopeMatch()) {
      throw new RefreshAccessException("Renew access key to Google Drive", cause);
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected Long readChangeId() throws RepositoryException, CloudDriveException {
    try {
      return rootNode().getProperty("gdrive:largestChangeId").getLong();
    } catch (PathNotFoundException e) {
      throw new CloudDriveException("Change id not found for the drive " + title());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void saveChangeId(Long id) throws CloudDriveException, RepositoryException {
    Node driveNode = rootNode();
    // will be saved in a single save of the drive command (sync)
    driveNode.setProperty("gdrive:largestChangeId", id);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void initDrive(Node driveNode) throws CloudDriveException, RepositoryException {
    super.initDrive(driveNode);

    About about = getUser().api().about();
    driveNode.setProperty("ecd:id", about.getRootFolderId());
    driveNode.setProperty("ecd:url", about.getSelfLink());
    driveNode.setProperty("gdrive:scopes", GoogleDriveAPI.SCOPES_STRING);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ConnectCommand getConnectCommand() throws DriveRemovedException, RepositoryException {
    return new Connect();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected SyncCommand getSyncCommand() throws DriveRemovedException,
                                        SyncNotSupportedException,
                                        RepositoryException {
    return new Sync();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudFileAPI createFileAPI() throws DriveRemovedException,
                                        SyncNotSupportedException,
                                        RepositoryException {
    return new FileAPI();
  }

  /**
   * Merge file's local and cloud parents (in case of move replace source on destination).
   * 
   * @param parentId {@link String} current local parent id
   * @param cloudParents {@link List} of {@link ParentReference} on cloud side
   * @param localParentIds {@link List} of parent ids locally
   * @return
   */
  protected List<ParentReference> mergeParents(String parentId,
                                               List<ParentReference> cloudParents,
                                               Collection<String> localParentIds) {
    List<ParentReference> parents = new ArrayList<ParentReference>();
    if (cloudParents != null && cloudParents.size() > 1) {
      for (ParentReference cp : cloudParents) {
        if (localParentIds.contains(cp.getId())) {
          parents.add(cp);
        }
      }
    }
    parents.add(new ParentReference().setId(parentId));
    return parents;
  }

  /**
   * {@inheritDoc}
   */
  protected String editLink(String fileLink, Node fileNode) throws RepositoryException {
    // file link and edit link are the same for Google Drive as for 28.01.2015
    return fileLink;
  }

  /**
   * Google Drive manages consumed size differently for uploaded (stored in content) and native documents.
   * File stored in content has a size, but native document has usage of quota only.
   * 
   * @param gf {@link File}
   * @return file size or consumed quota if the size not available
   */
  protected long fileSize(File gf) {
    Long size = gf.getFileSize();
    if (size == null) {
      size = gf.getQuotaBytesUsed();
      if (size == null) {
        size = -1l;
      }
    }
    return size;
  }
}
