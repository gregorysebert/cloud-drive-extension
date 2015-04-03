package org.exoplatform.clouddrive.rest;


import org.apache.chemistry.opencmis.client.api.Repository;
import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudDriveService;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.cmis.CMISUser;
import org.exoplatform.clouddrive.cmis.login.CodeAuthentication;
import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.cms.BasePath;
import org.exoplatform.services.cms.impl.DMSConfiguration;
import org.exoplatform.services.cms.impl.DMSRepositoryConfiguration;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.ext.hierarchy.NodeHierarchyCreator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.rest.resource.ResourceContainer;
import org.exoplatform.services.security.ConversationState;

import javax.annotation.security.RolesAllowed;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import java.util.List;


/**

 * CloudDrive ClouddriveRestSyncService

 */

@Path("/clouddrive")

public class ClouddriveRestSyncService implements ResourceContainer {

    protected final Log log = ExoLogger.getLogger("org.exoplatform.clouddrive.rest.ClouddriveRestSyncService");

    protected static final String SYNC_USER = "exo.clouddrive.alfresco.sync.user";

    protected static final String SYNC_PASSWORD = "exo.clouddrive.alfresco.sync.password";

    protected static final String SYNC_URL = "exo.clouddrive.alfresco.sync.url";

    protected static final String DRIVE_NAME = "exo.clouddrive.alfresco.drive.name";
    protected static final String DRIVE_WORKSPACE = "exo.clouddrive.alfresco.drive.workspace";
    protected static final String DRIVE_PERMISSIONS = "exo.clouddrive.alfresco.drive.permissions";
    protected static final String DRIVE_VIEWS = "exo.clouddrive.alfresco.drive.views";
    protected static final String DRIVE_ICON = "exo.clouddrive.alfresco.drive.icon";
    protected static final String DRIVE_PATH = "exo.clouddrive.alfresco.drive.homePath";
    protected static final String DRIVE_VIEW_REFERENCES = "exo.clouddrive.alfresco.drive.viewPreferences";
    protected static final String DRIVE_VIEW_NON_DOCUMENT = "exo.clouddrive.alfresco.drive.viewNonDocument";
    protected static final String DRIVE_VIEW_SIDEBAR = "exo.clouddrive.alfresco.drive.viewSideBar";
    protected static final String DRIVE_SHOW_HIDDEN_NODE = "exo.clouddrive.alfresco.drive.showHiddenNode";
    protected static final String DRIVE_ALLOW_CREATE_FOLDER = "exo.clouddrive.alfresco.drive.allowCreateFolders";

    private NodeHierarchyCreator nodeHierarchyCreator_;

    private static String JCR_WORKSPACE = "exo:workspace" ;
    private static String JCR_PERMISSIONS = "exo:accessPermissions" ;
    private static String JCR_VIEWS = "exo:views" ;
    private static String JCR_ICON = "exo:icon" ;
    private static String JCR_PATH = "exo:path" ;
    private static String JCR_VIEW_REFERENCES = "exo:viewPreferences" ;
    private static String JCR_VIEW_NON_DOCUMENT = "exo:viewNonDocument" ;
    private static String JCR_VIEW_SIDEBAR = "exo:viewSideBar" ;
    private static String JCR_SHOW_HIDDEN_NODE = "exo:showHiddenNode" ;
    private static String JCR_ALLOW_CREATE_FOLDER = "exo:allowCreateFolders" ;


  @GET

  @Path("/init")

  @RolesAllowed({"administrators"}) 

  public String init()
  {

      // 1. Authentication in Alfresco
      try {
          log.info("Call init");
          log.info("Get init param from exo.properties : user=" + PropertyManager.getProperty(SYNC_USER) + ", url=" + PropertyManager.getProperty(SYNC_URL));
          log.info("Connecting to alfresco using cmis");
          CodeAuthentication codeAuth = (CodeAuthentication) ExoContainerContext.getCurrentContainer().getComponentInstance(CodeAuthentication.class);
          String code = codeAuth.authenticate(PropertyManager.getProperty(SYNC_URL),PropertyManager.getProperty(SYNC_USER), PropertyManager.getProperty(SYNC_PASSWORD));

          CloudDriveService cloudDrives = (CloudDriveService) ExoContainerContext.getCurrentContainer().getComponentInstance(CloudDriveService.class);
          CloudProvider cmisProvider = cloudDrives.getProvider("cmis");
          CMISUser cmisUser = (CMISUser) cloudDrives.authenticate(cmisProvider, code);

       // We get the Alfresco Repo
          List<Repository> repoList = cmisUser.getRepositories();
          org.apache.chemistry.opencmis.client.api.Repository repoAlfresco = repoList.get(0);

          cmisUser.setRepositoryId(repoAlfresco.getId());

       // We set CMIS Repo
          codeAuth.setCodeContext(code, repoAlfresco.getId());
          RepositoryService repositoryService = (RepositoryService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(RepositoryService.class);
          repositoryService.setCurrentRepositoryName(System.getProperty("gatein.jcr.repository.default"));

          SessionProviderService sessionProviders = (SessionProviderService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(SessionProviderService.class);
          SessionProvider sessionProvider = new SessionProvider(ConversationState.getCurrent());
          sessionProvider.setCurrentRepository(repositoryService.getCurrentRepository());
          sessionProvider.setCurrentWorkspace("collaboration");
          sessionProviders.setSessionProvider(null, sessionProvider);
          Session session = sessionProviders.getSessionProvider(null).getSession(sessionProvider.getCurrentWorkspace(), sessionProvider.getCurrentRepository());


          // 3. We get the node
          ManageableRepository manageableRepository = repositoryService.getCurrentRepository();
          session = sessionProvider.getSession("collaboration", manageableRepository);

          Node alfrescoFolder = null;
          Node root = session.getRootNode();

          if (!root.hasNode("Alfresco")) {
              log.info("Alfresco node doesn't exist-- Creating Alfresco jcr node");
              alfrescoFolder = root.addNode("Alfresco", "nt:unstructured");
              session.save();
          }

          addDrive();

          CloudDrive cmisRepoDrive = cloudDrives.findDrive(alfrescoFolder);

          if (cmisRepoDrive == null) {
              // Let's create the Drive
              log.info("Creating CloudDrive...");
              cmisRepoDrive = cloudDrives.createDrive(cmisUser, alfrescoFolder);
          }
          else   log.info("Retrieving CloudDrive...");

           // Synchronization
          log.info("Connecting to cmis");
          cmisRepoDrive.connect().await();

          log.info("Sync drive");
          cmisRepoDrive.synchronize().await();

      } catch (Exception e) {
          log.error("Login in Alfresco error");
          e.printStackTrace();
      }
      return "SyncAll ok";
  }

    @GET

    @Path("/syncAll")

    @RolesAllowed({"administrators"})

    public String syncAll()
    {

        // 1. Authentication in Alfresco
        try {
            log.info("Call syncAll");
            log.info("Get init param from exo.properties : user=" + PropertyManager.getProperty(SYNC_USER) + ", url=" + PropertyManager.getProperty(SYNC_URL));
            log.info("Connecting to alfresco using cmis");
            CodeAuthentication codeAuth = (CodeAuthentication) ExoContainerContext.getCurrentContainer().getComponentInstance(CodeAuthentication.class);
            String code = codeAuth.authenticate(PropertyManager.getProperty(SYNC_URL),PropertyManager.getProperty(SYNC_USER), PropertyManager.getProperty(SYNC_PASSWORD));

            CloudDriveService cloudDrives = (CloudDriveService) ExoContainerContext.getCurrentContainer().getComponentInstance(CloudDriveService.class);
            CloudProvider cmisProvider = cloudDrives.getProvider("cmis");
            CMISUser cmisUser = (CMISUser) cloudDrives.authenticate(cmisProvider, code);

            // We get the Alfresco Repo
            List<Repository> repoList = cmisUser.getRepositories();
            org.apache.chemistry.opencmis.client.api.Repository repoAlfresco = repoList.get(0);

            cmisUser.setRepositoryId(repoAlfresco.getId());

            // We set CMIS Repo
            codeAuth.setCodeContext(code, repoAlfresco.getId());
            RepositoryService repositoryService = (RepositoryService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(RepositoryService.class);
            repositoryService.setCurrentRepositoryName(System.getProperty("gatein.jcr.repository.default"));

            SessionProviderService sessionProviders = (SessionProviderService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(SessionProviderService.class);
            SessionProvider sessionProvider = new SessionProvider(ConversationState.getCurrent());
            sessionProvider.setCurrentRepository(repositoryService.getCurrentRepository());
            sessionProvider.setCurrentWorkspace("collaboration");
            sessionProviders.setSessionProvider(null, sessionProvider);
            Session session = sessionProviders.getSessionProvider(null).getSession(sessionProvider.getCurrentWorkspace(), sessionProvider.getCurrentRepository());


            // 3. We get the node
            ManageableRepository manageableRepository = repositoryService.getCurrentRepository();
            session = sessionProvider.getSession("collaboration", manageableRepository);

            Node alfrescoFolder = null;
            Node root = session.getRootNode();

            if (root.hasNode("Alfresco")) {
                alfrescoFolder = root.getNode("Alfresco");
            }

            CloudDrive cmisRepoDrive = cloudDrives.findDrive(alfrescoFolder);

            if (cmisRepoDrive == null) {
                // Let's create the Drive
                log.info("Could connect to cloud drive, please run init first ...");
                cmisRepoDrive = cloudDrives.createDrive(cmisUser, alfrescoFolder);
            }
            else
            {
                // Synchronization
                log.info("Connecting to cmis");
                cmisRepoDrive.connect().await();

                log.info("Sync drive");
                cmisRepoDrive.synchronize().await();
            }

        } catch (Exception e) {
            log.error("Login in Alfresco error");
            e.printStackTrace();
        }
        return "SyncAll ok";
    }



      @GET

  @Path("/syncItem/{cmisId}")
  @RolesAllowed({"administrators"})
  public String syncItem(@PathParam("cmisId") String cmisId) {
      OrganizationService organizationService = (OrganizationService) ExoContainerContext.getCurrentContainer()

                                                                                         .getComponentInstanceOfType(OrganizationService.class);
    return "Sync item "+cmisId
            +" ok";


  }

    /**
     * Register new drive node with specified DriveData
     * @throws Exception
     */
    private void addDrive() throws Exception {
        nodeHierarchyCreator_ = (NodeHierarchyCreator) ExoContainerContext.getCurrentContainer()
                .getComponentInstanceOfType(NodeHierarchyCreator.class);
        Session sessionDrive =  getDmsRepositorySession();
        String drivesPath = nodeHierarchyCreator_.getJcrPath(BasePath.EXO_DRIVES_PATH);
        Node driveHome = (Node)sessionDrive.getItem(drivesPath) ;
        Node driveNode = null ;
        if(!driveHome.hasNode(PropertyManager.getProperty(DRIVE_NAME))){
            driveNode = driveHome.addNode(PropertyManager.getProperty(DRIVE_NAME), "exo:drive");
            driveNode.setProperty(JCR_WORKSPACE, PropertyManager.getProperty(DRIVE_WORKSPACE)) ;
            driveNode.setProperty(JCR_PERMISSIONS, PropertyManager.getProperty(DRIVE_PERMISSIONS)) ;
            driveNode.setProperty(JCR_PATH, PropertyManager.getProperty(DRIVE_PATH)) ;
            driveNode.setProperty(JCR_VIEWS, PropertyManager.getProperty(DRIVE_VIEWS)) ;
            driveNode.setProperty(JCR_ICON, PropertyManager.getProperty(DRIVE_ICON)) ;
            driveNode.setProperty(JCR_VIEW_REFERENCES, PropertyManager.getProperty(DRIVE_VIEW_REFERENCES)) ;
            driveNode.setProperty(JCR_VIEW_NON_DOCUMENT, PropertyManager.getProperty(DRIVE_VIEW_NON_DOCUMENT)) ;
            driveNode.setProperty(JCR_VIEW_SIDEBAR, PropertyManager.getProperty(DRIVE_VIEW_SIDEBAR)) ;
            driveNode.setProperty(JCR_SHOW_HIDDEN_NODE, PropertyManager.getProperty(DRIVE_SHOW_HIDDEN_NODE)) ;
            driveNode.setProperty(JCR_ALLOW_CREATE_FOLDER, PropertyManager.getProperty(DRIVE_ALLOW_CREATE_FOLDER)) ;
            driveHome.save() ;
            sessionDrive.save() ;
        }
    }

    /**
     * Return Session object with specified repository name
     * @return Session object
     * @throws Exception
     */
    private Session getDmsRepositorySession() throws RepositoryException {
        DMSConfiguration dmsConfiguration= (DMSConfiguration) ExoContainerContext.getCurrentContainer()
                .getComponentInstanceOfType(DMSConfiguration.class);
        RepositoryService repositoryService = (RepositoryService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(RepositoryService.class);

        ManageableRepository manaRepository = repositoryService.getCurrentRepository();
        DMSRepositoryConfiguration dmsRepoConfig = dmsConfiguration.getConfig();
        return manaRepository.getSystemSession(dmsRepoConfig.getSystemWorkspace());
    }

    }
