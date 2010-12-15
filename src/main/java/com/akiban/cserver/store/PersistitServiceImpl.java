package com.akiban.cserver.store;

import java.io.File;
import java.io.FileNotFoundException;
import java.rmi.RemoteException;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.akiban.cserver.CServerUtil;
import com.akiban.cserver.service.config.ConfigurationService;
import com.akiban.cserver.service.session.Session;
import com.persistit.Exchange;
import com.persistit.Persistit;
import com.persistit.Volume;
import com.persistit.exception.PersistitException;
import com.persistit.logging.ApacheCommonsLogAdapter;

public class PersistitServiceImpl implements PersistitService {

    private final static int MEGA = 1024 * 1024;

    private static final Log LOG = LogFactory.getLog(PersistitServiceImpl.class
            .getName());

    private static final String SERVER_MODULE_NAME = "cserver";

    private static final String PERSISTIT_MODULE_NAME = "persistit";

    private static final String DATAPATH_PROP_NAME = "datapath";

    private static final String BUFFER_SIZE_PROP_NAME = "buffersize";

    private static final String BUFFER_COUNT_PROP_NAME = "buffercount";

    private static final String DEFAULT_DATAPATH = "/tmp/chunkserver_data";

    // Must be one of 1024, 2048, 4096, 8192, 16384:
    private static final int DEFAULT_BUFFER_SIZE = 8192;

    // Generally this is used only for unit tests and is
    // overridden by memory allocation calculation.
    private static final int DEFAULT_BUFFER_COUNT = 1024;

    private final static long MEMORY_RESERVATION = 64 * MEGA;

    private final static float PERSISTIT_ALLOCATION_FRACTION = 0.5f;

    private static final String FIXED_ALLOCATION_PROPERTY_NAME = "fixed";

    static final int MAX_TRANSACTION_RETRY_COUNT = 10;

    final static String SCHEMA_TREE_NAME = "_schema_";

    final static String BY_ID = "byId";

    final static String BY_NAME = "byName";

    final static String VOLUME_NAME = "akiban_data";

    private final ConfigurationService configService;

    private Persistit db;

    public PersistitServiceImpl(final ConfigurationService configService) {
        this.configService = configService;
    }

    public synchronized void start() throws Exception {
        assert db == null;
        final Properties properties = configService.getModuleConfiguration(
                PERSISTIT_MODULE_NAME).getProperties();
        //
        // This section modifies the properties gotten from the
        // default configuration plus chunkserver.properties. It
        //
        // (a) copies cserver.datapath to datapath
        // (b) sets the buffersize property if null
        // (c) sets the buffercount property if null.
        //
        // Copies the cserver.datapath property to the Persistit properties set.
        // This allows Persistit to perform substitution of ${datapath} with
        // the server-specified home directory.
        //
        final String datapath = configService.getProperty(SERVER_MODULE_NAME,
                DATAPATH_PROP_NAME, DEFAULT_DATAPATH);
        properties.setProperty(DATAPATH_PROP_NAME, datapath);
        ensureDirectoryExists(datapath, false);

        final boolean isFixedAllocation = "true".equals(configService
                .getProperty(SERVER_MODULE_NAME,
                        FIXED_ALLOCATION_PROPERTY_NAME, "false"));
        if (!properties.contains(BUFFER_SIZE_PROP_NAME)) {
            properties.setProperty(BUFFER_SIZE_PROP_NAME,
                    String.valueOf(DEFAULT_BUFFER_SIZE));
        }
        final int bufferSize = Integer.parseInt(properties
                .getProperty(BUFFER_SIZE_PROP_NAME));
        if (!properties.contains(BUFFER_COUNT_PROP_NAME)) {
            properties.setProperty(BUFFER_COUNT_PROP_NAME,
                    String.valueOf(bufferCount(bufferSize, isFixedAllocation)));
        }
        //
        // Now we're ready to create the Persistit instance.
        //
        db = new Persistit();
        db.setPersistitLogger(new ApacheCommonsLogAdapter(LOG));
        db.initialize(properties);

        if (LOG.isInfoEnabled()) {
            LOG.info("PersistitStore datapath=" + db.getProperty("datapath")
                    + (bufferSize / 1024) + "k_buffers="
                    + db.getProperty("buffer.count." + bufferSize));
        }

    }

    public void setDisplayFilter(final PersistitStore store) {
        try {
            db.getManagement().setDisplayFilter(
                    new RowDataDisplayFilter(store, db.getManagement()
                            .getDisplayFilter()));
        } catch (RemoteException e) {
            // Ignore - Not important
        }
    }

    /**
     * Makes sure the given directory exists, optionally trying to create it.
     * 
     * @param path
     *            the directory to check for or create
     * @param alreadyTriedCreatingDirectory
     *            whether we've already tried to create the directory
     * @throws FileNotFoundException
     *             if the given path exists but is not a directory, or can't be
     *             created
     */
    private static void ensureDirectoryExists(String path,
            boolean alreadyTriedCreatingDirectory) throws FileNotFoundException {
        File dir = new File(path);
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                throw new FileNotFoundException(String.format(
                        "%s exists but is not a directory", dir));
            }
        } else {
            if (alreadyTriedCreatingDirectory) {
                throw new FileNotFoundException(String.format(
                        "Unable to create directory %s. Permissions problem?",
                        dir));
            } else {
                dir.mkdirs();
                ensureDirectoryExists(path, true);
            }
        }
    }

    private int bufferCount(final int bufferSize,
            final boolean isFixedAllocation) {
        if (isFixedAllocation) {
            return DEFAULT_BUFFER_COUNT;
        }
        final long allocation = (long) ((CServerUtil.availableMemory() - MEMORY_RESERVATION) * PERSISTIT_ALLOCATION_FRACTION);
        final int allocationPerBuffer = (int) (bufferSize * 1.5);
        return Math.max(512, (int) (allocation / allocationPerBuffer));
    }

    public synchronized void stop() throws Exception {
        if (db != null) {
            db.shutdownGUI();
            db.close();
            db = null;
        }
    }

    @Override
    public PersistitService cast() {
        return this;
    }

    @Override
    public Class<PersistitService> castClass() {
        return PersistitService.class;
    }

    public Persistit getDb() {
        return db;
    }

    public Exchange getExchange(final Session session, final String schemaName,
            final String treeName) throws Exception {
        final Volume volume = mappedVolume(schemaName, treeName);
        return getExchange(session, volume, treeName);
    }
    
    private Exchange getExchange(final Session session, final Volume volume, final String treeName) throws Exception {
        return null;
    }

    public void releaseExchange(final Session session, final Exchange exchange) {

    }

    public void visitStorage(final StorageVisitor visitor,
            final String treeName, final Object context) throws Exception {
        final Volume sysVol = db.getSystemVolume();
        final Volume txnVol = db.getTransactionVolume();
        for (final Volume volume : db.getVolumes()) {
            if (volume != sysVol && volume != txnVol) {
                final Exchange exchange = db.getExchange(volume, treeName,
                        false);
                if (exchange != null) {
                    visitor.visit(exchange, context);
                }
            }
        }

    }
    
    private Volume mappedVolume(final String schema, final String treeName) {
        return null;
    }

}
