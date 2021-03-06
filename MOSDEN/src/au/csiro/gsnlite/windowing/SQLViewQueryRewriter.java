package au.csiro.gsnlite.windowing;

import java.io.Serializable;
import java.sql.SQLException;

import au.csiro.gsnlite.Main;
import au.csiro.gsnlite.beans.DataField;
import au.csiro.gsnlite.beans.StreamElement;
import au.csiro.gsnlite.storage.StorageManager;
import au.csiro.gsnlite.utils.Logger;
import au.csiro.gsnlite.utils.SQLUtils;



public abstract class SQLViewQueryRewriter extends QueryRewriter {

	private static transient Logger logger             = Logger.getInstance();
	private static String TAG = "SQLViewQueryRewriter.class";
	
    
    protected static StorageManager storageManager = Main.getWindowStorage();
    public static final CharSequence VIEW_HELPER_TABLE = Main.getWindowStorage().tableNameGeneratorInString("_SQL_VIEW_HELPER_".toLowerCase());
    private static DataField[] viewHelperFields = new DataField[]{new DataField("u_id", "varchar(17)")};

    static {
        try {
            if (storageManager.tableExists(VIEW_HELPER_TABLE)) {
                storageManager.executeDropTable(VIEW_HELPER_TABLE);
            }
            storageManager.executeCreateTable(VIEW_HELPER_TABLE, viewHelperFields, false);
        } catch (SQLException e) {
            logger.error(TAG, e.getMessage());
        }
    }
    protected StringBuilder cachedSqlQuery;

    @Override
    public boolean initialize() {
        if (streamSource == null) {
            throw new RuntimeException("Null Pointer Exception: streamSource is null");
        }
        try {
            // Initializing view helper table entry for this stream source
            storageManager.executeInsert(VIEW_HELPER_TABLE, viewHelperFields, new StreamElement(viewHelperFields,
                    new Serializable[]{streamSource.getUIDStr().toString()}, -1));

            storageManager.executeCreateView(streamSource.getUIDStr(), createViewSQL());
        } catch (SQLException e) {
            logger.error(TAG, e.getMessage());
            return false;
        }
        return true;
    }

    @Override
    public StringBuilder rewrite(String query) {
        if (streamSource == null) {
            throw new RuntimeException("Null Pointer Exception: streamSource is null");
        }
        return SQLUtils.newRewrite(query, streamSource.getAlias(), streamSource.getUIDStr());
    }

    @Override
    public void dispose() {
        if (streamSource == null) {
            throw new RuntimeException("Null Pointer Exception: streamSource is null");
        }
        try {
            storageManager.executeDropView(streamSource.getUIDStr());
        } catch (SQLException e) {
            logger.error(TAG, e.getMessage());
        }
    }

    @Override
    public boolean dataAvailable(long timestamp) {
        try {
            //TODO : can we use prepareStatement instead of creating a new query each time
            StringBuilder query = new StringBuilder("update ").append(VIEW_HELPER_TABLE);
            query.append(" set timed=").append(timestamp).append(" where u_id='").append(streamSource.getUIDStr());
            query.append("' ");
            storageManager.executeUpdate(query);
            if (storageManager.isThereAnyResult(new StringBuilder("select * from ").append(streamSource.getUIDStr()))) {
                if (logger.isDebugEnabled()) {
                    logger.debug(TAG, streamSource.getWrapper().getWrapperName() + " - Output stream produced/received from a wrapper " + streamSource.toString());
                }
                return streamSource.windowSlided();
            }
        } catch (Exception e) {
            logger.error(TAG, e.getMessage());
        }
        return false;
    }

    public abstract CharSequence createViewSQL();
}
