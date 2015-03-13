package org.exoplatform.clouddrive.rest;


import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.rest.resource.ResourceContainer;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;


/**

 * CloudDrive ClouddriveRestSyncService

 */

@Path("/clouddrive")

public class ClouddriveRestSyncService implements ResourceContainer {


  @GET

  @Path("/syncAll")

  @RolesAllowed({"administrators"}) 

  public String syncAll()
  {

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
}