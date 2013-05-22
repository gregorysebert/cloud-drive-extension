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
package org.exoplatform.clouddrive.rest;

import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.security.RolesAllowed;
import javax.jcr.Item;
import javax.jcr.LoginException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.ws.rs.CookieParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.exoplatform.clouddrive.BaseCloudDriveListener;
import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudDriveEvent;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudDriveService;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.ProviderNotAvailableException;
import org.exoplatform.clouddrive.UserAlreadyConnectedException;
import org.exoplatform.clouddrive.CloudDrive.Command;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.rest.resource.ResourceContainer;
import org.exoplatform.services.security.ConversationState;

/**
 * REST service responsible for connection of cloud drives to local JCR nodes. <br>
 * Handles following workflow:
 * <ul>
 * <li>Initiate user request</li>
 * <li>Authenticate user</li>
 * <li>Starts connect command</li>
 * <li>Check connect status</li>
 * </ul>
 * <br>
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: ConnectService.java 00000 Sep 13, 2012 pnedonosko $
 */
@Path("/clouddrive/connect")
@Produces(MediaType.APPLICATION_JSON)
public class ConnectService implements ResourceContainer {

  public static final String    CONNECT_COOKIE         = "cloud-drive-connect-id";

  public static final String    ERROR_COOKIE           = "cloud-drive-error";

  public static final String    INIT_COOKIE            = "cloud-drive-init-id";

  /**
   * Init cookie expire time in seconds.
   */
  public static final int       INIT_COOKIE_EXPIRE     = 60;                                       // 1min

  /**
   * Connect cookie expire time in seconds.
   */
  public static final int       CONNECT_COOKIE_EXPIRE  = 60;                                       // 1min

  /**
   * Error cookie expire time in seconds.
   */
  public static final int       ERROR_COOKIE_EXPIRE    = 10;                                       // 10sec

  /**
   * Connect request expire time in milliseconds.
   */
  public static final int       CONNECT_REQUEST_EXPIRE = CONNECT_COOKIE_EXPIRE * 1000;

  /**
   * Connect process expire time in milliseconds.
   */
  public static final int       CONNECT_PROCESS_EXPIRE = 60 * 60 * 1000;                           // 1hr

  protected static final Random random                 = new Random();

  protected static final Log    LOG                    = ExoLogger.getLogger(ConnectService.class);

  /**
   * Response builder for connect and state.
   */
  class ConnectResponse extends ServiceResponse {
    String    serviceUrl;

    int       progress;

    DriveInfo drive;

    String    error;

    String    location;

    ConnectResponse serviceUrl(String serviceUrl) {
      this.serviceUrl = serviceUrl;
      return this;
    }

    ConnectResponse progress(int progress) {
      this.progress = progress;
      return this;
    }

    ConnectResponse drive(DriveInfo drive) {
      this.drive = drive;
      return this;
    }

    ConnectResponse error(String error) {
      this.error = error;
      return this;
    }

    ConnectResponse location(String location) {
      this.location = location;
      return this;
    }

    ConnectResponse connectError(String error, String connectId, String host) {
      if (connectId != null) {
        cookie(CONNECT_COOKIE, connectId, "/", host, "Cloud Drive connect ID", 0, false);
      }
      cookie(ERROR_COOKIE, error, "/", host, "Cloud Drive connection error", ERROR_COOKIE_EXPIRE, false);
      this.error = error;

      return this;
    }

    ConnectResponse authError(String message, String host, String providerName, String initId, String baseHost) {
      if (initId != null) {
        // need reset previous cookie by expire time = 0
        cookie(INIT_COOKIE, initId, "/", baseHost, "Cloud Drive init ID", 0, false);
      }
      cookie(ERROR_COOKIE, message, "/", host, "Cloud Drive connection error", ERROR_COOKIE_EXPIRE, false);
      super.entity("<!doctype html><html><head><script type='text/javascript'> setTimeout(function() {window.close();}, 4000);</script></head><body><div id='messageString'>"
          + (providerName != null ? providerName + " return error: " + message : message)
          + "</div></body></html>");

      return this;
    }

    ConnectResponse authError(String message, String host) {
      return authError(message, host, null, null, null);
    }

    /**
     * @inherritDoc
     */
    @Override
    Response build() {
      if (drive != null) {
        super.entity(new CommandState(drive, error, progress, serviceUrl));
      } else if (error != null) {
        super.entity(new CommandState(error, progress, serviceUrl));
      } else if (location != null) {
        super.addHeader("Location", location);
        super.entity("<!doctype html><html><head></head><body><div id='redirectLink'>" + "<a href='"
            + location + "'>Use new location to the service.</a>" + "</div></body></html>");
      } // else - what was set in entity()
      return super.build();
    }
  }

  /**
   * Connect initialization record used during establishment of connect workflow.
   */
  class ConnectInit {
    final String        localUser;

    final CloudProvider provider;

    final URI           host;

    ConnectInit(String localUser, CloudProvider provider, URI host) {
      this.localUser = localUser;
      this.provider = provider;
      this.host = host;
    }
  }

  /**
   * Connect process record used in connect workflow. Also used to answer on state request.
   */
  class ConnectProcess extends BaseCloudDriveListener {
    final CloudDrive        drive;

    final Command           process;

    final String            name;

    final String            workspaceName;

    final Lock              lock = new ReentrantLock();

    Throwable               error;

    /**
     * ConversationState used by initail thread.
     */
    final ConversationState conversation;

    ConnectProcess(String name, String workspaceName, CloudDrive drive, ConversationState conversation) throws CloudDriveException,
        RepositoryException {
      this.drive = drive;
      this.name = name;
      this.workspaceName = workspaceName;
      this.conversation = conversation;
      this.drive.addListener(this); // listen to remove from active map
      this.process = drive.connect(true);

      LOG.info(name + " connect started.");
    }

    void rollback() throws RepositoryException {
      // set correct user's ConversationState
      ConversationState.setCurrent(conversation);
      // set correct SessionProvider
      sessionProviders.setSessionProvider(null, new SessionProvider(conversation));

      SessionProvider provider = new SessionProvider(conversation);
      Session session = provider.getSession(workspaceName, jcrService.getCurrentRepository());

      try {
        session.getItem(drive.getPath()).remove();
        session.save();
      } catch (PathNotFoundException e) {
        // not found - ok
      } catch (DriveRemovedException e) {
        // removed - ok
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onError(CloudDriveEvent event, Throwable error) {
      lock.lock();
      // unregister listener
      drive.removeListener(this);
      this.error = error;
      try {
        rollback();
      } catch (Throwable e) {
        LOG.warn("Error removing the drive Node connected with error (" + error.getMessage() + "). "
                     + e.getMessage(),
                 e);
      } finally {
        lock.unlock();
        // XXX no big sense to log it here, error should be reported in place it occurs
        // LOG.error(name + " connect failed.", error);
      }
    }

    @Override
    public void onConnect(CloudDriveEvent event) {
      // remove from active here
      active.values().remove(this);
      // unregister listener
      drive.removeListener(this);

      LOG.info(name + " successfully connected.");
    }
  }

  protected final CloudDriveService           cloudDrives;

  protected final DriveServiceLocator         locator;

  protected final SessionProviderService      sessionProviders;

  protected final RepositoryService           jcrService;

  protected final Map<UUID, CloudUser>        authenticated = new ConcurrentHashMap<UUID, CloudUser>();

  protected final Map<UUID, ConnectInit>      initiated     = new ConcurrentHashMap<UUID, ConnectInit>();

  protected final Map<UUID, Long>             timeline      = new ConcurrentHashMap<UUID, Long>();

  /**
   * Connections in progress.
   */
  protected final Map<String, ConnectProcess> active        = new ConcurrentHashMap<String, ConnectProcess>();

  protected final Thread                      connectsCleaner;

  /**
   * REST cloudDrives uses {@link CloudDriveService} for actual job.
   * 
   * @param {@link CloudDriveService} cloudDrives
   */
  public ConnectService(CloudDriveService cloudDrives,
                        DriveServiceLocator locator,
                        RepositoryService jcrService,
                        SessionProviderService sessionProviders) {
    this.cloudDrives = cloudDrives;
    this.locator = locator;
    this.jcrService = jcrService;
    this.sessionProviders = sessionProviders;

    this.connectsCleaner = new Thread("Cloud Drive connections cleaner") {
      @Override
      public void run() {
        boolean interrupted = false;
        while (!interrupted) {
          final long now = System.currentTimeMillis();

          for (Iterator<Map.Entry<UUID, Long>> titer = timeline.entrySet().iterator(); titer.hasNext();) {
            Map.Entry<UUID, Long> t = titer.next();
            if (now >= t.getValue()) {
              authenticated.remove(t.getKey());
              initiated.remove(t.getKey());
              titer.remove();
            }
          }

          for (Iterator<Map.Entry<String, ConnectProcess>> cpiter = active.entrySet().iterator(); cpiter.hasNext();) {
            ConnectProcess cp = cpiter.next().getValue();
            if (cp != null) {
              long expireTime = cp.process.getStartTime() + CONNECT_PROCESS_EXPIRE;
              if (now >= expireTime) {
                cpiter.remove();
              }
            }
          }

          try {
            Thread.sleep(CONNECT_REQUEST_EXPIRE);
          } catch (InterruptedException e) {
            LOG.info(getName() + " interrupted: " + e.getMessage());
            interrupted = true;
          }
        }
      }
    };
    connectsCleaner.start();
  }

  /**
   * Start connection of user's Cloud Drive to local JCR node.
   * 
   * @param uriInfo - request info TODO need it?
   * @param workspace - workspace for cloud drive node
   * @param path - path to user's node to what connect the drive
   * @param jsessionsId
   * @param jsessionsIdSSO
   * @param connectId
   * @param initId
   * @return {@link Response}
   */
  @POST
  @RolesAllowed("users")
  public Response connectStart(@Context UriInfo uriInfo,
                               @FormParam("workspace") String workspace,
                               @FormParam("path") String path,
                               @CookieParam("JSESSIONID") Cookie jsessionsId,
                               @CookieParam("JSESSIONIDSSO") Cookie jsessionsIdSSO,
                               @CookieParam(CONNECT_COOKIE) Cookie connectId,
                               @CookieParam(INIT_COOKIE) Cookie initId) {

    ConnectResponse resp = new ConnectResponse();
    String host = serviceHost(uriInfo);
    if (connectId != null) {
      UUID cid = UUID.fromString(connectId.getValue());
      CloudUser user = authenticated.remove(cid);
      timeline.remove(cid);

      Node userNode = null;
      Node driveNode = null;
      if (user != null) {
        try {
          ConversationState convo = ConversationState.getCurrent();
          if (convo == null) {
            LOG.error("Error connect drive for user " + user.getEmail()
                + ". User identity not set: ConversationState.getCurrent() is null");
            return resp.connectError("User identity not set.", cid.toString(), host)
                       .status(Status.INTERNAL_SERVER_ERROR)
                       .build();
          }

          if (workspace != null) {
            if (path != null) {
              SessionProvider sp = sessionProviders.getSessionProvider(null);
              Session userSession = sp.getSession(workspace, jcrService.getCurrentRepository());
              try {
                Item item = userSession.getItem(path);
                if (item.isNode()) {
                  userNode = (Node) item;
                  String name = driveName(user);
                  String processId = processId(workspace, userNode.getPath(), name);

                  // rest it by expire = 0
                  resp.cookie(CONNECT_COOKIE,
                              cid.toString(),
                              "/",
                              locator.getBaseHost() != null ? locator.getBaseHost() : uriInfo.getRequestUri()
                                                                                             .getHost(),
                              "Cloud Drive connect ID",
                              0,
                              false);

                  ConnectProcess connect = active.get(processId);
                  if (connect == null || connect.error != null) {
                    // initiate connect process if it is not already active or a previous had an exception
                    try {
                      driveNode = userNode.getNode(name);
                    } catch (PathNotFoundException pnte) {
                      // node not found - add it
                      try {
                        driveNode = userNode.addNode(name, JCRLocalCloudDrive.NT_FOLDER);
                        userNode.save();
                      } catch (RepositoryException e) {
                        rollback(userNode, null);
                        LOG.error("Error creating node for the drive of user " + user.getEmail()
                            + ". Cannot create node under " + path, e);
                        return resp.connectError("Error creating node for the drive: storage error.",
                                                 cid.toString(),
                                                 host)
                                   .status(Status.INTERNAL_SERVER_ERROR)
                                   .build();
                      }
                    }

                    // state check url
                    resp.serviceUrl(uriInfo.getRequestUriBuilder()
                                           .queryParam("workspace", workspace)
                                           .queryParam("path", driveNode.getPath())
                                           .build(new Object[0])
                                           .toASCIIString());

                    try {
                      CloudDrive local = cloudDrives.createDrive(user, driveNode);
                      if (local.isConnected()) {
                        // exists and already connected

                        resp.status(Status.CREATED); // OK vs CREATED?
                        DriveInfo drive = DriveInfo.create(local);
                        resp.drive(drive);
                        LOG.info(drive.getEmail() + " already connected.");
                      } else {
                        // a new or exist but not connected - connect it
                        connect = new ConnectProcess(name, workspace, local, convo);
                        active.put(processId, connect);

                        resp.status(connect.process.isDone() ? Status.CREATED : Status.ACCEPTED);
                        resp.progress(connect.process.getProgress());
                        DriveInfo drive = DriveInfo.create(local, connect.process.getFiles());
                        resp.drive(drive);
                      }
                    } catch (UserAlreadyConnectedException e) {
                      LOG.warn(e.getMessage(), e);
                      resp.connectError(e.getMessage(), cid.toString(), host).status(Status.CONFLICT);
                    } catch (CloudDriveException e) {
                      rollback(userNode, driveNode);
                      LOG.error("Error connecting drive for user " + user + ", " + workspace + ":" + path, e);
                      resp.connectError("Error connecting drive. " + e.getMessage(), cid.toString(), host)
                          .status(Status.INTERNAL_SERVER_ERROR);
                    }
                  } else {
                    // else, such connect already in progress (probably was started by another request)
                    // client can warn the user or try use check url to get that work status
                    String message = "Connect to " + connect.name
                        + " already posted and currently in progress.";
                    LOG.warn(message);

                    try {
                      // do response with that process lock as done in state()
                      connect.lock.lock();
                      // state check url
                      resp.serviceUrl(uriInfo.getRequestUriBuilder()
                                             .queryParam("workspace", workspace)
                                             .queryParam("path", connect.drive.getPath())
                                             .build(new Object[0])
                                             .toASCIIString());
                      resp.progress(connect.process.getProgress());
                      resp.drive(DriveInfo.create(connect.drive));
                      resp.connectError(message, cid.toString(), host).status(Status.CONFLICT);
                    } finally {
                      connect.lock.unlock();
                    }
                  }
                } else {
                  LOG.warn("Item " + workspace + ":" + path + " not a node.");
                  resp.connectError("Not a node.", cid.toString(), host).status(Status.PRECONDITION_FAILED);
                }
              } finally {
                userSession.logout();
              }
            } else {
              return Response.status(Status.BAD_REQUEST).entity("Null path.").build();
            }
          } else {
            return Response.status(Status.BAD_REQUEST).entity("Null workspace.").build();
          }
        } catch (LoginException e) {
          LOG.warn("Error login to connect drive " + workspace + ":" + path + ". " + e.getMessage());
          resp.connectError("Authentication error.", cid.toString(), host).status(Status.UNAUTHORIZED);
        } catch (RepositoryException e) {
          LOG.error("Error connecting drive for user " + user + ", node " + workspace + ":" + path, e);
          rollback(userNode, driveNode);
          resp.connectError("Error connecting drive: storage error.", cid.toString(), host)
              .status(Status.INTERNAL_SERVER_ERROR);
        } catch (Throwable e) {
          LOG.error("Error connecting drive for user " + user + ", node " + workspace + ":" + path, e);
          rollback(userNode, driveNode);
          resp.connectError("Error connecting drive: runtime error.", cid.toString(), host)
              .status(Status.INTERNAL_SERVER_ERROR);
        }
      } else {
        LOG.warn("User not authenticated for connectId " + connectId);
        resp.connectError("User not authenticated.", cid.toString(), host).status(Status.BAD_REQUEST);
      }
    } else {
      LOG.warn("Connect ID not set");
      resp.error("Connection not initiated properly.").status(Status.BAD_REQUEST);
    }

    return resp.build();
  }

  /**
   * Return drive connect status.
   * 
   * @param uriInfo
   * @param workspace
   * @param path
   * @return {@link Response}
   */
  @GET
  @RolesAllowed("users")
  public Response connectState(@Context UriInfo uriInfo,
                               @QueryParam("workspace") String workspace,
                               @QueryParam("path") String path) {

    ConnectResponse resp = new ConnectResponse();
    resp.serviceUrl(uriInfo.getRequestUri().toASCIIString());

    String processId = processId(workspace, path);
    try {
      ConnectProcess connect = active.get(processId);
      if (connect != null) {
        // connect in progress or recently finished
        int progress = connect.process.getProgress();
        resp.progress(progress);

        // lock to prevent async process to remove the drive (on its fail) while doing state response
        connect.lock.lock();
        try {
          if (connect.error != null) {
            // KO:error during the connect
            // TODO hack for 503 from Google, move this logic to Google connector, as well as access_denied
            String error = connect.error.getMessage();
            if (error.indexOf("backendError") >= 0) {
              error = "Google backend error. Try again later.";
            }
            resp.error(error).status(Status.INTERNAL_SERVER_ERROR);
          } else {
            // OK:connected or accepted (in progress)
            // XXX don't send files each time but on done only
            if (connect.process.isDone()) {
              DriveInfo drive = DriveInfo.create(connect.drive, connect.process.getFiles());
              resp.drive(drive);
              resp.status(Status.CREATED);
            } else {
              DriveInfo drive = DriveInfo.create(connect.drive);
              resp.drive(drive);
              resp.status(Status.ACCEPTED);
            }
          }
        } catch (RepositoryException e) {
          LOG.warn("Error reading drive " + processId + ". " + e.getMessage(), e);
          // KO:read error
          resp.error("Error reading drive: storage error.").status(Status.INTERNAL_SERVER_ERROR);
        } catch (DriveRemovedException e) {
          LOG.warn("Drive removed " + processId, e);
          // KO:removed
          resp.error("Drive removed '" + connect.name + "'.").status(Status.BAD_REQUEST);
        } finally {
          connect.lock.unlock();
        }
      } else {
        // logic for those who will ask the service to check if drive connected
        try {
          SessionProvider sp = sessionProviders.getSessionProvider(null);
          Session userSession = sp.getSession(workspace, jcrService.getCurrentRepository());

          try {
            Item item = userSession.getItem(path);
            if (item.isNode()) {
              Node driveNode = (Node) item;
              CloudDrive drive = cloudDrives.findDrive(driveNode);
              if (drive != null) {
                // return OK: connected
                resp.progress(Command.COMPLETE).drive(DriveInfo.create(drive)).ok();
              } else {
                // return OK: drive not found, disconnected or belong to another user
                LOG.warn("Item " + workspace + ":" + path + " not a cloud file or drive not connected.");
                resp.status(Status.NO_CONTENT);
              }
            } else {
              LOG.warn("Not a Node '" + item.getPath() + "'");
              // KO:not a Node
              resp.error("Not a node.").status(Status.PRECONDITION_FAILED);
            }
          } catch (PathNotFoundException e) {
            LOG.warn("Node not found " + processId, e);
            // KO:not found
            resp.error("Node not found.").status(Status.NOT_FOUND);
          } catch (DriveRemovedException e) {
            LOG.warn("Drive removed " + processId, e);
            // KO:removed
            resp.error("Drive removed.").status(Status.BAD_REQUEST);
          }
        } catch (RepositoryException e) {
          LOG.error("Error reading connected drive '" + processId + "'", e);
          // KO:storage error
          resp.error("Error reading connected drive: storage error.").status(Status.INTERNAL_SERVER_ERROR);
        }
      }
    } catch (Throwable e) {
      LOG.error("Error getting state of drive '" + processId + "'. ", e);
      resp.error("Error getting state of drive.").status(Status.INTERNAL_SERVER_ERROR);
    }

    return resp.build();
  }

  /**
   * Returns connecting cloud user page. It's an empty page with attributes on the body for client-side code
   * handling the connect procedure. Some providers use this service url as callback after authorization
   * (Google Drive). <br>
   * This method is GET because of possibility of redirect on it.
   * 
   * @param uriInfo - request info TODO need it?
   * @param providerId - provider id, see more in {@link CloudProvider}
   * @param key - authentication key (OAuth2 code for example)
   * @param error - error from the provider
   * @param jsessionsId
   * @param jsessionsIdSSO
   * @param initId - init cookie
   * @return response with connecting page or error
   */
  @GET
  @Path("/{providerid}/")
  @Produces(MediaType.TEXT_HTML)
  public Response userAuth(@Context UriInfo uriInfo,
                           @PathParam("providerid") String providerId,
                           @QueryParam("code") String key,
                           @QueryParam("state") String repoName,
                           @QueryParam("error") String error,
                           @CookieParam("JSESSIONID") Cookie jsessionsId,
                           @CookieParam("JSESSIONIDSSO") Cookie jsessionsIdSSO,
                           @CookieParam(INIT_COOKIE) Cookie initId) {

    // LOG.info("JSESSIONID: " + jsessionsId);
    // LOG.info("JSESSIONIDSSO: " + jsessionsIdSSO);
    // LOG.info(INIT_COOKIE + ": " + initId);

    ConnectResponse resp = new ConnectResponse();
    String baseHost = locator.getBaseHost(); // TODO cleanup System.getProperty("tenant.masterhost");
    if (baseHost == null) {
      baseHost = uriInfo.getRequestUri().getHost();
    } else if (repoName != null) {
      if (baseHost.equals(uriInfo.getRequestUri().getHost())) {
        // need redirect to actual service URL, TODO remove state query parameter
        // resp.location(uriInfo.getRequestUri().toString().replace(masterHost, repoName + "." + masterHost));
        resp.location(locator.getServiceHost(repoName, baseHost));
        return resp.status(Status.MOVED_PERMANENTLY).build(); // redirect
      }
    }

    if (initId != null) {
      try {
        UUID iid = UUID.fromString(initId.getValue());
        ConnectInit connect = initiated.remove(iid);
        timeline.remove(iid);

        if (connect != null) {
          CloudProvider cloudProvider = connect.provider;
          if (cloudProvider.getId().equals(providerId)) {
            if (error == null) {
              // it's the same as initiated request
              if (key != null) {
                try {
                  CloudUser user = cloudDrives.authenticate(cloudProvider, key);

                  UUID connectId = generateId(user.getEmail() + key);
                  authenticated.put(connectId, user);
                  timeline.put(connectId, System.currentTimeMillis() + (CONNECT_COOKIE_EXPIRE * 1000) + 5000);

                  // This cookie will be set on host of initial request (i.e. on
                  // host of calling app)
                  resp.cookie(CONNECT_COOKIE,
                              connectId.toString(),
                              "/",
                              connect.host.getHost(),
                              "Cloud Drive connect ID",
                              CONNECT_COOKIE_EXPIRE,
                              false);

                  // reset it by expire time = 0
                  resp.cookie(INIT_COOKIE, iid.toString(), "/", baseHost, "Cloud Drive init ID", 0, false);

                  resp.entity("<!doctype html><html><head><script type='text/javascript'> window.close();</script></head><body><div id='messageString'>Connecting to "
                      + cloudProvider.getName() + "</div></body></html>");

                  return resp.ok().build();
                } catch (CloudDriveException e) {
                  LOG.warn("Error authenticating user to access " + cloudProvider.getName(), e);
                  return resp.authError("Authentication error on " + cloudProvider.getName(),
                                        connect.host.getHost(),
                                        cloudProvider.getName(),
                                        iid.toString(),
                                        baseHost)
                             // TODO UNAUTHORIZED ?
                             .status(Status.BAD_REQUEST)
                             .build();
                }
              } else {
                LOG.warn("Key required for " + cloudProvider.getName());
                return resp.authError("Key required for " + cloudProvider.getName(),
                                      connect.host.getHost(),
                                      cloudProvider.getName(),
                                      iid.toString(),
                                      baseHost)
                           .status(Status.BAD_REQUEST)
                           .build();
              }
            } else {
              // we have an error from provider
              LOG.warn(cloudProvider.getName() + " error: " + error);

              // TODO hack for access_denied from Google, move this logic to Google connector
              if (error.indexOf("access_denied") >= 0) {
                resp.authError("Acccess denied.",
                               connect.host.getHost(),
                               cloudProvider.getName(),
                               iid.toString(),
                               baseHost).status(Status.FORBIDDEN);
              } else {
                resp.authError(error,
                               connect.host.getHost(),
                               cloudProvider.getName(),
                               iid.toString(),
                               baseHost).status(Status.BAD_REQUEST);
              }

              return resp.build();
            }
          } else {
            LOG.error("Authentication was not initiated for " + providerId + " but request to "
                + cloudProvider.getId() + " recorded with id " + initId);
            return resp.authError("Authentication not initiated to " + cloudProvider.getName(),
                                  connect.host.getHost(),
                                  cloudProvider.getName(),
                                  iid.toString(),
                                  baseHost)
                       .status(Status.INTERNAL_SERVER_ERROR)
                       .build();
          }
        } else {
          LOG.warn("Authentication was not initiated for " + providerId + " and id " + initId);
          return resp.authError("Authentication request expired. Try again.",
                                baseHost,
                                null,
                                iid.toString(),
                                baseHost)
                     .status(Status.BAD_REQUEST)
                     .build();
        }
      } catch (Throwable e) {
        LOG.error("Error initializing drive provider by id " + providerId, e);
        return resp.authError("Error initializing drive provider", baseHost)
                   .status(Status.INTERNAL_SERVER_ERROR)
                   .build();
      }
    } else {
      LOG.warn("Authentication id not set for provider id " + providerId + " and key " + key);
      return resp.authError("Authentication not initiated or expired. Try again.", baseHost)
                 .status(Status.BAD_REQUEST)
                 .build();
    }
  }

  /**
   * Initiates connection to cloud drive. Used to get a Provider and remember a user connect request in the
   * service. It will be used later for authentication.
   * 
   * @param uriInfo - request with base URI
   * @param providerId - provider name see more in {@link CloudProvider}
   * @return response with
   */
  @GET
  @Path("/init/{providerid}/")
  @RolesAllowed("users")
  public Response userInit(@Context UriInfo uriInfo,
                           @PathParam("providerid") String providerId,
                           @CookieParam("JSESSIONID") Cookie jsessionsId,
                           @CookieParam("JSESSIONIDSSO") Cookie jsessionsIdSSO) {

    // LOG.info("JSESSIONID: " + jsessionsId);
    // LOG.info("JSESSIONIDSSO: " + jsessionsIdSSO);

    ConnectResponse resp = new ConnectResponse();
    try {
      CloudProvider cloudProvider = cloudDrives.getProvider(providerId);
      ConversationState convo = ConversationState.getCurrent();
      if (convo != null) {
        String localUser = convo.getIdentity().getUserId();
        String host = serviceHost(uriInfo);

        UUID initId = generateId(localUser);
        initiated.put(initId, new ConnectInit(localUser, cloudProvider, new URI("http://" + host
            + "/portal/rest")));
        timeline.put(initId, System.currentTimeMillis() + (INIT_COOKIE_EXPIRE * 1000) + 5000);

        resp.cookie(INIT_COOKIE,
                    initId.toString(),
                    "/",
                    host,
                    "Cloud Drive init ID",
                    INIT_COOKIE_EXPIRE,
                    false);
        return resp.entity(cloudProvider).ok().build();
      } else {
        LOG.warn("ConversationState not set to initialize connect to " + cloudProvider.getName());
        return resp.error("User not authenticated to connect " + cloudProvider.getName())
                   .status(Status.UNAUTHORIZED)
                   .build();
      }
    } catch (ProviderNotAvailableException e) {
      LOG.warn("Provider not found for id '" + providerId + "'", e);
      return resp.error("Provider not found.").status(Status.BAD_REQUEST).build();
    } catch (Throwable e) {
      LOG.error("Error initializing user request for drive provider " + providerId, e);
      return resp.error("Error initializing user request.").status(Status.INTERNAL_SERVER_ERROR).build();
    }
  }

  protected UUID generateId(String name) {
    StringBuilder s = new StringBuilder();
    s.append(name);
    s.append(System.currentTimeMillis());
    s.append(String.valueOf(random.nextLong()));

    return UUID.nameUUIDFromBytes(s.toString().getBytes());
  }

  protected String processId(String workspace, String parentPath, String driveName) {
    return workspace + ":" + parentPath + "/" + driveName;
  }

  protected String processId(String workspace, String nodePath) {
    return workspace + ":" + nodePath;
  }

  protected String driveName(CloudUser user) {
    return user.getProvider().getName() + " - " + user.getEmail();
  }

  /**
   * Rollback connect changes.
   * 
   * @param userNode {@link Node}
   * @param driveNode {@link Node}
   * @return true if rolledback
   */
  protected boolean rollback(Node userNode, Node driveNode) {
    try {
      if (userNode != null) {
        if (driveNode != null) {
          driveNode.remove();
          userNode.save();
        }

        userNode.refresh(false);
        return true;
      }
    } catch (Throwable e) {
      LOG.warn("Error rolling back the user node: " + e.getMessage(), e);
    }
    return false;
  }

  protected String serviceHost(UriInfo uriInfo) {
    String host = locator.getBaseHost();
    if (host == null) {
      host = uriInfo.getRequestUri().getHost();
    }
    return host;
  }
}
