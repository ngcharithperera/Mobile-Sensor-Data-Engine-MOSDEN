package au.csiro.gsnlite.vsensor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;

import java.io.Writer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import android.os.Environment;
import au.csiro.gsnlite.Main;
import au.csiro.gsnlite.beans.AddressBean;
import au.csiro.gsnlite.beans.DataField;
import au.csiro.gsnlite.beans.InputStream;
import au.csiro.gsnlite.beans.Mappings;
import au.csiro.gsnlite.beans.Modifications;
import au.csiro.gsnlite.beans.StreamSource;
import au.csiro.gsnlite.exceptions.VirtualSensorInitializationFailedException;
import au.csiro.gsnlite.utils.Logger;
import au.csiro.gsnlite.wrappers.AbstractWrapper;
import au.csiro.gsnlite.wrappers.WrappersUtil;

public class VSensorLoader extends Thread {
 

	public static final String                                     VSENSOR_POOL                        = "VSENSOR-POOL";

	public static final String                                     STREAM_SOURCE                       = "STREAM-SOURCE";

	public static final String                                     INPUT_STREAM                        = "INPUT-STREAM";

	
	private static Logger logger = Logger.getInstance();
    private static String TAG = "VSensorLoader.class";

	/**
	 * Mapping between the AddressBean and DataSources
	 */
	private  final List < AbstractWrapper > activeWrappers                   = new ArrayList< AbstractWrapper >( );

	//private StorageManager                                         sm                      = StorageManager.getInstance ( );

	private String                                                 pluginsDir;

	private boolean                                                isActive                              = true;

    private static int                                             VSENSOR_LOADER_THREAD_COUNTER       = 0;

    private static VSensorLoader singleton = null;

    private ArrayList<VSensorStateChangeListener> changeListeners = new ArrayList<VSensorStateChangeListener>();

	public void addVSensorStateChangeListener(VSensorStateChangeListener listener) {
		if (!changeListeners.contains(listener))
			changeListeners.add(listener);
	}

	public void removeVSensorStateChangeListener(VSensorStateChangeListener listener) {
		changeListeners.remove(listener);
	}
	
	public boolean fireVSensorLoading(VSensorConfig config) {
		for (VSensorStateChangeListener listener : changeListeners)
			if (!listener.vsLoading(config))
				return false;
		return true;
	}
	
	public boolean fireVSensorUnLoading(VSensorConfig config) {
		for (VSensorStateChangeListener listener : changeListeners)
			if (!listener.vsUnLoading(config)) {
				logger.error (TAG,"Unloading failed !",new RuntimeException("Unloading : "+config.getName()+" is failed."));
				return false;
			}
		return true;
	}


	public VSensorLoader() {

	}
	public VSensorLoader ( String pluginsPath ) {
		this.pluginsDir = pluginsPath;
	}

    public static VSensorLoader getInstance(String path) {
        if (singleton == null){
        	//changing path to point to android conf directory	
        	File sdcard = Environment.getExternalStorageDirectory();
        	path = sdcard.getAbsolutePath() + "/" + path;
            singleton = new VSensorLoader(path);
        }
        return singleton;
    }
	
	public void startLoading() {
		Thread thread = new Thread ( this );
		thread.setName ( "VSensorLoader-Thread" + VSENSOR_LOADER_THREAD_COUNTER++ );
		thread.start ( );
	}

	public void run ( ) {
		if ( Main.getStorage((VSensorConfig)null) == null || Main.getWindowStorage() == null ) { // Checks only if the default storage and the window storage are defined.
			logger.error (TAG, "The Storage Manager shouldn't be null, possible a BUG." );
			return;
		}
		while ( isActive ) {
			try {
				loadPlugin ( );
			} catch ( Exception e ) {
				logger.error (TAG, e.getMessage ( ) , e );
			}
		}
	}

    public synchronized void loadVirtualSensor(String vsConfigurationFileContent, String fileName) throws Exception {
        String filePath = getVSConfigurationFilePath(fileName);
        File file = new File(filePath);
        if (!file.exists()) {
            try {
                // Create the VS configuration file
                Writer fw = new BufferedWriter(new FileWriter(filePath, true));
                fw.write(vsConfigurationFileContent);
                fw.flush();
                // Try to load it
                if ( ! loadPlugin(fileName)) {
                    throw new Exception("Failed to load the Virtual Sensor: " + fileName + " because there is syntax error in the configuration file. Please check the configuration file and try again.");
                }
            }
            catch (Exception e) {
                logger.warn(TAG,e.getMessage(), e);
                if (file.exists()) file.delete();
                throw e;
            }
        } else {
            logger.warn(TAG,"The configuration file:" + filePath + " already exist.");
            throw new Exception("The configuration file:" + filePath + " already exist.");
        }
    }

     public static String getVSConfigurationFilePath(String fileName) {
        return Main.DEFAULT_VIRTUAL_SENSOR_DIRECTORY + File.separator + fileName + ".xml";
    }

    public synchronized void loadPlugin() throws SQLException {

        Modifications modifications = getUpdateStatus(pluginsDir);
        ArrayList<VSensorConfig> removeIt = modifications.getRemove();
        ArrayList<VSensorConfig> addIt = modifications.getAdd();
        //Loading the virtual sensor config files from the virtual sensor directory
        for (VSensorConfig configFile : removeIt) {
            removeVirtualSensor(configFile);
        }
        for (VSensorConfig vs : addIt) {
            loadPlugin(vs);
        }
        
		try {
			Thread.sleep ( 3000 );
		} catch ( InterruptedException e ) {
			logger.error (TAG, e.getMessage ( ) , e );
		}
    }

    public synchronized boolean loadPlugin(String fileFilterName) throws SQLException {
        Modifications modifications = getUpdateStatus(pluginsDir, fileFilterName);
        ArrayList<VSensorConfig> addIt = modifications.getAdd();

        boolean found = false;
        for (VSensorConfig config : addIt){
            if (config.getName().equals(fileFilterName)) {
                found = true;
                break;
            }
        }
        if (!found)
            return false;
        else
            return loadPlugin(addIt.get(0));
    }


    private synchronized boolean loadPlugin(VSensorConfig vs) throws SQLException {
    	  if (!isVirtualSensorValid(vs))
              return false;

          VirtualSensor pool = new VirtualSensor(vs);
          try {
              if (createInputStreams(pool) == false) {
                  logger.error (TAG,"loading the >" + vs.getName() + "< virtual sensor is stoped due to error (TAG,s) in preparing the input streams.");
                  return false;
              }
          } catch (InstantiationException e2) {
              logger.error (TAG,e2.getMessage(), e2);
          } catch (IllegalAccessException e2) {
              logger.error (TAG,e2.getMessage(), e2);
          }
          try {
              if (!Main.getStorage(vs).tableExists(vs.getName(), vs.getOutputStructure()))
                  Main.getStorage(vs).executeCreateTable(vs.getName(), vs.getOutputStructure(), pool.getConfig().getIsTimeStampUnique());
              else
                  logger.info(TAG,"Reusing the existing " + vs.getName() + " table.");
          } catch (SQLException e) {
              if (e.getMessage().toLowerCase().contains("table already exists")) {
                  logger.error (TAG,e.getMessage());
                  if (logger.isInfoEnabled()) logger.info(TAG,e.getMessage(), e);
                  logger.error (TAG,new StringBuilder().append("Loading the virtual sensor specified in the file : ").append(vs.getFileName()).append(" failed").toString());
                  logger.error (TAG,new StringBuilder().append("The table : ").append(vs.getName()).append(" is exists in the database specified in :").append(
                          Main.getContainerConfig().getContainerFileName()).append(".").toString());
                  logger.error (TAG,"Solutions : ");
                  logger.error (TAG,new StringBuilder().append("1. Change the virtual sensor name, in the : ").append(vs.getFileName()).toString());
                  logger.error (TAG,new StringBuilder().append("2. Change the URL of the database in ").append(Main.getContainerConfig().getContainerFileName()).append(
                          " and choose another database.").toString());
                  logger.error (TAG,new StringBuilder().append("3. Rename/Move the table with the name : ").append(Main.getContainerConfig().getContainerFileName()).append(" in the database.")
                          .toString());
                  logger.error (TAG,new StringBuilder().append("4. Change the overwrite-tables=\"true\" (be careful, this will overwrite all the data previously saved in ").append(
                          vs.getName()).append(" table )").toString());
              } else {
                  logger.error (TAG,e.getMessage(), e);
              }
              return false;
          }
          logger.warn(TAG,new StringBuilder("adding : ").append(vs.getName()).append(" virtual sensor[").append(vs.getFileName()).append("]").toString());
          if (Mappings.addVSensorInstance(pool)) {
              try {
                  fireVSensorLoading(pool.getConfig());
                  pool.start();
              } catch (VirtualSensorInitializationFailedException e1) {
                  logger.error (TAG,"Creating the virtual sensor >" + vs.getName() + "< failed.", e1);
                  removeVirtualSensor(vs);
                  return false;
              }
          } else {
              //TODO: release all vs resources
          }
          return true;
    }

    
	private void removeVirtualSensor(VSensorConfig configFile) {
		logger.warn(TAG, new StringBuilder ( ).append ( "removing : " ).append ( configFile.getName ( ) ).toString ( ) );
		VirtualSensor sensorInstance = Mappings.getVSensorInstanceByFileName ( configFile.getFileName ( ) );
		Mappings.removeFilename ( configFile.getFileName ( ) );
		removeAllVSResources ( sensorInstance );
	}

	public boolean isVirtualSensorValid(VSensorConfig configuration) {
		for ( InputStream is : configuration.getInputStreams ( ) ) {
			if ( !is.validate ( ) ) {
				logger.error ( TAG,new StringBuilder ( ).append ( "Adding the virtual sensor specified in " ).append ( configuration.getFileName ( ) ).append ( " failed because of one or more problems in configuration file." )
						.toString ( ) );
				logger.error (TAG, new StringBuilder ( ).append ( "Please check the file and try again" ).toString ( ) );
				return false;
			}
		}
		String vsName = configuration.getName ( );
		if ( Mappings.getVSensorConfig ( vsName ) != null ) {
			logger.error ( TAG,new StringBuilder ( ).append ( "Adding the virtual sensor specified in " ).append ( configuration.getFileName ( ) ).append ( " failed because the virtual sensor name used by " )
					.append ( configuration.getFileName ( ) ).append ( " is already used by : " ).append ( Mappings.getVSensorConfig ( vsName ).getFileName ( ) ).toString ( ) );
			logger.error (TAG, "Note that the virtual sensor name is case insensitive and all the spaces in it's name will be removed automatically." );
			return false;
		}

		if ( !isValidJavaIdentifier( vsName ) ) {
			logger.error ( TAG,new StringBuilder ( ).append ( "Adding the virtual sensor specified in " ).append ( configuration.getFileName ( ) ).append (
			" failed because the virtual sensor name is not following the requirements : " ).toString ( ) );
			logger.error ( TAG,"The virtual sensor name is case insensitive and all the spaces in it's name will be removed automatically." );
			logger.error ( TAG,"That the name of the virutal sensor should starting by alphabetical character and they can contain numerical characters afterwards." );
			return false;
		}
		return true;
	}

	static protected boolean isValidJavaIdentifier(final String name) {
		boolean valid = false;
		while (true) {
			if (false == Character.isJavaIdentifierStart(name.charAt(0))) 
				break;
			valid = true;
			final int count = name.length();
			for (int i = 1; i < count; i++) {
				if (false == Character.isJavaIdentifierPart(name.charAt(i))) {
					valid = false;
					break;
				}
			}
			break;
		}
		return valid;
	}

	public void removeAllVSResources ( VirtualSensor pool ) {
		VSensorConfig config = pool.getConfig ( );
		pool.closePool ( );
		final String vsensorName = config.getName ( );
		if ( logger.isInfoEnabled ( ) ) logger.info (TAG, new StringBuilder ( ).append ( "Releasing previously used resources used by [" ).append ( vsensorName ).append ( "]." ).toString ( ) );
		for ( InputStream inputStream : config.getInputStreams ( ) ) {
			for ( StreamSource streamSource : inputStream.getSources ( ) ) 
				releaseStreamSource(streamSource);
			inputStream.release();
		}
		// sm.renameTable(vsensorName,vsensorName+"Before"+System.currentTimeMillis());
		logger.debug (TAG,"Total change Listeners:"+changeListeners.size());
		fireVSensorUnLoading(pool.getConfig());
		//  this.sm.dropTable ( config.getName ( ) );
	}

	public void releaseStreamSource(StreamSource streamSource) {
		final AbstractWrapper wrapper = streamSource.getWrapper ( );
		streamSource.getInputStream().getRenamingMapping().remove(streamSource.getAlias());
		try {
			wrapper.removeListener(streamSource);
		} catch (SQLException e) {
			logger.error (TAG,e.getMessage(),e);
			logger.error (TAG,"Release the resources failed !");
		}
		if ( !wrapper.isActive()) {//This stream source is the only listener
			logger.debug (TAG,"The wrapper:"+wrapper.getName()+" is removed.");
			activeWrappers.remove ( wrapper );
		}else {
			logger.debug (TAG,"The wrapper:"+wrapper.getName()+" is not released as it is still used by other virtual sensors.");
		}
	}

    public static Modifications getUpdateStatus(String virtualSensorsPath) {

        return getUpdateStatus(virtualSensorsPath, null);
    }

        public static Modifications getUpdateStatus(String virtualSensorsPath, String filterFileName) {
            ArrayList<String> remove = new ArrayList<String>();
            ArrayList<String> add = new ArrayList<String>();

            String[] previous = Mappings.getAllKnownFileName();

            FileFilter filter = new FileFilter ( ) {
                public boolean accept ( File file ) {
                    if ( !file.isDirectory ( ) && file.getName ( ).endsWith ( ".xml" ) && !file.getName ( ).startsWith ( "." ) ) return true;
                    return false;
                }
            };

            File files[] = new File(virtualSensorsPath).listFiles(filter);
            // --- preparing the remove list
            // Removing those in the previous which are not existing the new files
            // or modified.
            main:
            for (String pre : previous) {
                for (File curr : files)
                    if (pre.equals(curr.getAbsolutePath()) && (Mappings.getLastModifiedTime(pre) == curr.lastModified()))
                        continue main;
                remove.add(pre);
            }
            // ---adding the new files to the Add List a new file should added if
            //
            // 1. it's just deployed.
            // 2. it's modification time changed.

            main:
            for (File cur : files) {
                for (String pre : previous)
                    if (cur.getAbsolutePath().equals(pre) && (cur.lastModified() == Mappings.getLastModifiedTime(pre)))
                        continue main;
                add.add(cur.getAbsolutePath());
            }
            Modifications result = new Modifications(add, remove);
            return result;
        }
	

	/**
	 * The properties file contains information on wrappers for stream sources.
	 * FIXME : The body of CreateInputStreams is incomplete b/c in the case of an
	 * error it should remove the resources.
	 * @throws IllegalAccessException 
	 * @throws InstantiationException 
	 */
	public boolean createInputStreams ( VirtualSensor pool ) throws InstantiationException, IllegalAccessException {
		if ( logger.isDebugEnabled ( ) ) logger.debug (TAG, new StringBuilder ( ).append ( "Preparing input streams for: " ).append ( pool.getConfig().getName ( ) ).toString ( ) );
		if ( pool.getConfig().getInputStreams ( ).size ( ) == 0 ) logger.warn(TAG, new StringBuilder ( "There is no input streams defined for *" ).append ( pool.getConfig().getName ( ) ).append ( "*" ).toString ( ) );
		for ( Iterator < InputStream > inputStreamIterator = pool.getConfig().getInputStreams ( ).iterator ( ) ; inputStreamIterator.hasNext ( ) ; ) {
			InputStream inputStream = inputStreamIterator.next ( );
			for ( StreamSource  dataSouce : inputStream.getSources ( )) {
				if ( prepareStreamSource ( pool.getConfig(),inputStream , dataSouce ) == false ){
					return false;
					}
				// TODO if one stream source fails all the resources used by other successfuly initialized stream sources
				// for this input stream should be released.
			}
			inputStream.setPool (pool );
		}
		return true;
	}
	/**
	 * Tries to find a wrapper first from the active wrappers or instantiates a new one and puts it in the cache.
	 * @param addressBean
	 * @return
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 * FIXME: COPIED_FOR_SAFE_STOAGE
	 */
	public AbstractWrapper findWrapper(AddressBean addressBean) throws InstantiationException, IllegalAccessException {
			if ( Main.getWrapperClass ( addressBean.getWrapper ( ) ) == null ) {
				logger.error (TAG, "The wrapper >" + addressBean.getWrapper ( ) + "< is not defined in the >" + WrappersUtil.DEFAULT_WRAPPER_PROPERTIES_FILE + "< file." );
				return null;
			}
			AbstractWrapper wrapper = ( AbstractWrapper ) Main.getWrapperClass ( addressBean.getWrapper ( ) ).newInstance ( );
			wrapper.setActiveAddressBean ( addressBean );
			boolean initializationResult = wrapper.initialize (  );
			if ( initializationResult == false )
				return null;
			try {
				logger.warn(TAG, "Wrapper name: "+wrapper.getWrapperName()+ " -- view name "+ wrapper.getDBAliasInStr());
				if (!Main.getWindowStorage().tableExists(wrapper.getDBAliasInStr(),wrapper.getOutputFormat()))
					Main.getWindowStorage().executeCreateTable ( wrapper.getDBAliasInStr ( ) , wrapper.getOutputFormat ( ),wrapper.isTimeStampUnique() );
			} catch ( SQLException e ) {
				logger.error ( TAG,e.getMessage ( ) , e );
				return null;
			}
//			wrapper.start ( ); //moved to the VSensorPool
			activeWrappers.add ( wrapper );

			return wrapper;
	}
	public boolean prepareStreamSource ( VSensorConfig vsensorConfig,InputStream inputStream , StreamSource streamSource  ) throws InstantiationException, IllegalAccessException {
		streamSource.setInputStream(inputStream);
		AbstractWrapper wrapper = null;
		for ( AddressBean addressBean : streamSource.getAddressing ( ) ) {
			addressBean.setInputStreamName(inputStream.getInputStreamName());
			addressBean.setVirtualSensorName(vsensorConfig.getName());
			wrapper = findWrapper(addressBean);
			try {
				if (wrapper!=null && prepareStreamSource( streamSource,wrapper.getOutputFormat(),wrapper)) 
					break;
				else
					//TODO: remove wrapper from activeWrappers and release its resources
					wrapper=null;
			} catch (SQLException e) {
				logger.error (TAG,e.getMessage(),e);
				logger.error (TAG,"Preparation of the stream source failed : "+streamSource.getAlias()+ " from the input stream : "+inputStream.getInputStreamName());
			}
		}
		return (wrapper!=null);
	}

	public boolean prepareStreamSource ( StreamSource streamSource ,DataField[] outputformat, AbstractWrapper wrapper ) throws InstantiationException, IllegalAccessException, SQLException {
		if (outputformat==null) {
			logger.error (TAG,"Preparing the stream source failed because the wrapper : "+wrapper.getWrapperName()+" returns null for the >getOutputStructure< method!");
			return false;
		}
		streamSource.setWrapper ( wrapper );
		streamSource.getInputStream().addToRenamingMapping(streamSource.getAlias(), streamSource.getUIDStr());
		return true;
	}

	public void stopLoading ( ) {
		this.isActive = false;
		this.interrupt ( );
		for ( String configFile : Mappings.getAllKnownFileName ( ) ) {
			VirtualSensor sensorInstance = Mappings.getVSensorInstanceByFileName ( configFile );
			removeAllVSResources ( sensorInstance );
			logger.warn(TAG, "Removing the resources associated with : " + sensorInstance.getConfig ( ).getFileName ( ) + " [done]." );
		}
		try {
			Main.getWindowStorage().shutdown( );
            Iterator<VSensorConfig> iter = Mappings.getAllVSensorConfigs();
            while (iter.hasNext()) {
                Main.getStorage(iter.next()).shutdown();
            }
		} catch ( SQLException e ) {
			logger.error (TAG,e.getMessage(),e);
		}finally {			
		}
	}
}
