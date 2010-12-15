package com.akiban.cserver.manage;

import java.net.URL;
import java.net.URLClassLoader;

import com.akiban.cserver.CServer;
import com.akiban.cserver.CustomQuery;
import com.akiban.cserver.service.session.SessionImpl;
import com.akiban.cserver.store.PersistitStore;

public class ManageMXBeanImpl implements ManageMXBean
{

    private final CServer cserver;
    
    private Class customClass;

    public ManageMXBeanImpl(final CServer cserver) {
        this.cserver = cserver;
    }

    @Override
    public void ping() {
        return;
    }

    @Override
    public void shutdown() {
        new Thread() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.exit(0);
            }
        }.start();
    }

    @Override
    public int getNetworkPort() {
        return cserver.port();
    }

    @Override
    public int getJmxPort() {
        int jmxPort = Integer
                .getInteger("com.sun.management.jmxremote.port", 0);
        return jmxPort;
    }

    @Override
    public boolean isVerboseLoggingEnabled() {
        return cserver.getServiceManager().getStore().isVerbose();
    }

    @Override
    public void disableVerboseLogging() {
        cserver.getServiceManager().getStore().setVerbose(false);
    }

    @Override
    public void enableVerboseLogging() {
        cserver.getServiceManager().getStore().setVerbose(true);
    }

    @Override
    public boolean isDeferIndexesEnabled() {
        return cserver.getServiceManager().getStore().isDeferIndexes();
    }

    @Override
    public void setDeferIndexes(final boolean defer) {
        getStore().setDeferIndexes(defer);
    }

    @Override
    public void buildIndexes(final String arg) {
        getStore().buildIndexes(new SessionImpl(), arg);
    }

    @Override
    public void deleteIndexes(final String arg) {
        getStore().deleteIndexes(arg);
    }

    @Override
    public void flushIndexes() {
        getStore().flushIndexes();
    }

    // TODO - temporary
    @Override
    public String copyBackPages() {
        try {
            getStore().getDb().copyBackPages();
        } catch (Exception e) {
            return e.toString();
        }
        return "done";
    }
    
    
    public String loadCustomQuery(final String className) {
        try {
            customClass = null;
            final URL url = new URL("file:///tmp/custom-classes/");
            final ClassLoader cl = new URLClassLoader(new URL[]{url});
            final Class<?> c = cl.loadClass(className);
            if (CustomQuery.class.isAssignableFrom(c)) {
                customClass = c;
                return "OK";
            } else {
                return c.getSimpleName() + " does not implement Runnable";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return e.toString();
        }
    }
    
    public String runCustomQuery() {
        try {
            final CustomQuery cq = (CustomQuery)(customClass.newInstance());
            cq.setStore(cserver.getServiceManager().getStore());
            cq.runQuery();
            return cq.getResult();
        } catch (Exception e) {
            e.printStackTrace();
            return e.toString();
        }
    }

    private PersistitStore getStore()
    {
        return ((PersistitStore) cserver.getServiceManager().getStore());
    }
}
