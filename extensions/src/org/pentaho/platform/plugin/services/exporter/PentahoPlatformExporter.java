package org.pentaho.platform.plugin.services.exporter;

import org.apache.commons.io.IOUtils;
import org.pentaho.database.model.DatabaseConnection;
import org.pentaho.database.model.IDatabaseConnection;
import org.pentaho.metadata.repository.IMetadataDomainRepository;
import org.pentaho.platform.api.repository.datasource.DatasourceMgmtServiceException;
import org.pentaho.platform.api.repository.datasource.IDatasourceMgmtService;
import org.pentaho.platform.api.engine.security.userroledao.IPentahoRole;
import org.pentaho.platform.api.engine.security.userroledao.IPentahoUser;
import org.pentaho.platform.api.engine.security.userroledao.IUserRoleDao;
import org.pentaho.platform.api.mt.ITenant;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.scheduler2.IScheduler;
import org.pentaho.platform.api.scheduler2.Job;
import org.pentaho.platform.api.scheduler2.SchedulerException;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.core.system.TenantUtils;
import org.pentaho.platform.plugin.services.importexport.DefaultExportHandler;
import org.pentaho.platform.plugin.services.importexport.ExportException;
import org.pentaho.platform.plugin.services.importexport.ExportFileNameEncoder;
import org.pentaho.platform.plugin.services.importexport.RoleExport;
import org.pentaho.platform.plugin.services.importexport.UserExport;
import org.pentaho.platform.plugin.services.importexport.ZipExportProcessor;
import org.pentaho.platform.plugin.services.importexport.exportManifest.bindings.ExportManifestMetadata;
import org.pentaho.platform.plugin.services.messages.Messages;
import org.pentaho.platform.plugin.services.metadata.IPentahoMetadataDomainRepositoryExporter;
import org.pentaho.platform.repository2.ClientRepositoryPaths;
import org.pentaho.platform.scheduler2.versionchecker.EmbeddedVersionCheckSystemListener;
import org.pentaho.platform.security.policy.rolebased.IRoleAuthorizationPolicyRoleBindingDao;
import org.pentaho.platform.web.http.api.resources.JobScheduleRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PentahoPlatformExporter extends ZipExportProcessor {

  private static final Logger log = LoggerFactory.getLogger( PentahoPlatformExporter.class );

  public static final String ROOT = "/";
  public static final String DATA_SOURCES_PATH_IN_ZIP = "_datasources/";
  public static final String METADATA_PATH_IN_ZIP = DATA_SOURCES_PATH_IN_ZIP + "metadata/";
  public static final String ANALYSIS_PATH_IN_ZIP = DATA_SOURCES_PATH_IN_ZIP + "analysis/";
  public static final String CONNECTIONS_PATH_IN_ZIP = DATA_SOURCES_PATH_IN_ZIP + "connections/";

  private File exportFile;
  protected ZipOutputStream zos;

  private IScheduler scheduler;
  private IMetadataDomainRepository metadataDomainRepository;
  private IDatasourceMgmtService datasourceMgmtService;

  public PentahoPlatformExporter( IUnifiedRepository repository ) {
    super( ROOT, repository, true );
    setUnifiedRepository( repository );
    addExportHandler( new DefaultExportHandler() );
  }

  public File performExport() throws ExportException, IOException {
    return this.performExport( null );
  }

  /**
   * Performs the export process, returns a zip File object
   *
   * @throws ExportException indicates an error in import processing
   */
  @Override
  public File performExport( RepositoryFile exportRepositoryFile ) throws ExportException, IOException {

    // always export root
    exportRepositoryFile = getUnifiedRepository().getFile( ROOT );

    // create temp file
    exportFile = File.createTempFile( EXPORT_TEMP_FILENAME_PREFIX, EXPORT_TEMP_FILENAME_EXT );
    exportFile.deleteOnExit();

    zos = new ZipOutputStream( new FileOutputStream( exportFile ) );

    exportFileContent( exportRepositoryFile );
    exportDatasources();
    exportMondrianSchemas();
    exportMetadataModels();
    exportSchedules();
    exportUsersAndRoles();
    exportMetastore();

    if ( this.withManifest ) {
      // write manifest to zip output stream
      ZipEntry entry = new ZipEntry( EXPORT_MANIFEST_FILENAME );
      zos.putNextEntry( entry );

      // pass output stream to manifest class for writing
      try {
        exportManifest.toXml( zos );
      } catch ( Exception e ) {
        // todo: add to messages.properties
        log.error( "Error generating export XML" );
      }

      zos.closeEntry();
    }

    zos.close();

    // clean up
    exportManifest = null;
    zos = null;

    return exportFile;
  }

  protected void exportDatasources() {
    log.debug( "export datasources" );
    // get all connection to export
    try {
      List<IDatabaseConnection> datasources = getDatasourceMgmtService().getDatasources();
      for ( IDatabaseConnection datasource : datasources ) {
        if ( datasource instanceof DatabaseConnection ) {
          exportManifest.addDatasource( (DatabaseConnection) datasource );
        }
      }
    } catch ( DatasourceMgmtServiceException e ) {
      log.warn( e.getMessage(), e );
    }
  }

  protected void exportMetadataModels() {
    log.debug( "export metadata models" );

    // get all of the metadata models
    Set<String> domainIds = getMetadataDomainRepository().getDomainIds();

    for ( String domainId : domainIds ) {
      // get all of the files for this model
      Map<String, InputStream> domainFilesData = getDomainFilesData( domainId );

      for ( String fileName : domainFilesData.keySet() ) {
        // write the file to the zip
        String path = METADATA_PATH_IN_ZIP + fileName;
        if ( !path.endsWith( ".xmi" ) ) {
          path += ".xmi";
        }
        ZipEntry zipEntry = new ZipEntry( new ZipEntry( ExportFileNameEncoder.encodeZipPathName( path ) ) );
        InputStream inputStream = domainFilesData.get( fileName );

        // add the info to the exportManifest
        ExportManifestMetadata metadata = new ExportManifestMetadata();
        metadata.setDomainId( domainId );
        metadata.setFile( path );
        exportManifest.addMetadata( metadata );

        try {
          zos.putNextEntry( zipEntry );
          IOUtils.copy( inputStream, zos );

        } catch ( IOException e ) {
          log.warn( e.getMessage(), e );
        } finally {
          IOUtils.closeQuietly( inputStream );
          try {
            zos.closeEntry();
          } catch ( IOException e ) {
            // can't close the entry of input stream
          }
        }
      }
    }
  }

  private void exportMondrianSchemas() {
    log.debug( "export mondrian schemas" );
    exportManifest.addMondrian( null );
  }

  protected void exportSchedules() {
    log.debug( "export schedules" );
    try {
      List<Job> jobs = getScheduler().getJobs( null );
      for ( Job job : jobs ) {
        if ( job.getJobName().equals( EmbeddedVersionCheckSystemListener.VERSION_CHECK_JOBNAME ) ) {
          // don't bother exporting the Version Checker schedule, it gets created automatically on server start
          // if it doesn't exist and fails if you try to import it due to a null ActionClass
          continue;
        }
        try {
          JobScheduleRequest scheduleRequest = ScheduleExportUtil.createJobScheduleRequest( job );
          exportManifest.addSchedule( scheduleRequest );
        } catch ( IllegalArgumentException e ) {
          log.warn( e.getMessage(), e );
        }
      }
    } catch ( SchedulerException e ) {
      log.error( Messages.getInstance().getString( "PentahoPlatformExporter.ERROR_EXPORTING_JOBS" ), e );
    }
  }


  protected void exportUsersAndRoles() {
    log.debug( "export users & roles" );

    IUserRoleDao roleDao = PentahoSystem.get( IUserRoleDao.class );
    IRoleAuthorizationPolicyRoleBindingDao roleBindingDao = PentahoSystem.get(
      IRoleAuthorizationPolicyRoleBindingDao.class );
    ITenant tenant = TenantUtils.getCurrentTenant();

    //User Export
    List<IPentahoUser> userList = roleDao.getUsers( tenant );
    for ( IPentahoUser user : userList ) {
      UserExport userExport = new UserExport();
      userExport.setUsername( user.getUsername() );
      for ( IPentahoRole role : roleDao.getUserRoles( tenant, user.getUsername() ) ) {
        userExport.setRole( role.getName() );
      }

      this.getExportManifest().addUserExport( userExport );
    }

    //RoleExport
    List<IPentahoRole> roles = roleDao.getRoles();
    for ( IPentahoRole role : roles ) {
      RoleExport roleExport = new RoleExport();
      roleExport.setRolename( role.getName() );
      roleExport.setPermission( roleBindingDao.getRoleBindingStruct( null ).bindingMap.get( role.getName() ) );
      this.getExportManifest().addRoleExport( roleExport );
    }
  }

  private void exportMetastore() {
    log.debug( "export the metastore" );
  }

  protected void exportFileContent( RepositoryFile exportRepositoryFile ) throws IOException, ExportException {
    // get the file path
    String filePath = new File( this.path ).getParent();
    if ( filePath == null ) {
      filePath = "/";
    }

    // send a response right away if not found
    if ( exportRepositoryFile == null ) {
      // todo: add to messages.properties
      throw new FileNotFoundException( "JCR file not found: " + this.path );
    }

    if ( exportRepositoryFile.isFolder() ) { // Handle recursive export
      exportManifest.getManifestInformation().setRootFolder( path.substring( 0, path.lastIndexOf( "/" ) + 1 ) );

      // don't zip root folder without name
      if ( !ClientRepositoryPaths.getRootFolderPath().equals( exportRepositoryFile.getPath() ) ) {
        zos.putNextEntry( new ZipEntry( ExportFileNameEncoder
          .encodeZipPathName( getZipEntryName( exportRepositoryFile, filePath ) ) ) );
      }
      exportDirectory( exportRepositoryFile, zos, filePath );

    } else {
      exportManifest.getManifestInformation().setRootFolder( path.substring( 0, path.lastIndexOf( "/" ) + 1 ) );
      exportFile( exportRepositoryFile, zos, filePath );
    }
  }

  protected Map<String, InputStream> getDomainFilesData( String domainId ) {
    return ( (IPentahoMetadataDomainRepositoryExporter) metadataDomainRepository ).getDomainFilesData( domainId );
  }

  public IScheduler getScheduler() {
    if ( scheduler == null ) {
      scheduler = PentahoSystem.get( IScheduler.class, "IScheduler2", null ); //$NON-NLS-1$
    }
    return scheduler;
  }

  public void setScheduler( IScheduler scheduler ) {
    this.scheduler = scheduler;
  }

  public IMetadataDomainRepository getMetadataDomainRepository() {
    if ( metadataDomainRepository == null ) {
      metadataDomainRepository = PentahoSystem.get( IMetadataDomainRepository.class,
        PentahoSessionHolder.getSession() );
    }
    return metadataDomainRepository;
  }

  public void setMetadataDomainRepository( IMetadataDomainRepository metadataDomainRepository ) {
    this.metadataDomainRepository = metadataDomainRepository;
  }

  public IDatasourceMgmtService getDatasourceMgmtService() {
    if ( datasourceMgmtService == null ) {
      datasourceMgmtService = PentahoSystem.get( IDatasourceMgmtService.class, PentahoSessionHolder.getSession() );
    }
    return datasourceMgmtService;
  }

  public void setDatasourceMgmtService( IDatasourceMgmtService datasourceMgmtService ) {
    this.datasourceMgmtService = datasourceMgmtService;
  }
}
