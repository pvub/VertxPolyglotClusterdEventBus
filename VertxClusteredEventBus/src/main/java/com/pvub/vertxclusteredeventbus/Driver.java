package com.pvub.vertxclusteredeventbus;

import io.vertx.core.VertxOptions;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.core.Vertx;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main driver class to start the vertx instance
 * @author Udai
 */
public class Driver {
    private Vertx   m_vertx = null;
    private Logger  m_logger;
    public static void main(String args[]) {
        Driver d = new Driver();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Running Shutdown Hook");
                d.stop();
            }
        });
        d.start();
    }
    
    public Driver() {
        m_logger = LoggerFactory.getLogger("DRIVER");
    }

    public void start() {
        m_logger.info("Starting Driver");
        ClusterManager mgr = new HazelcastClusterManager();
        VertxOptions vOptions = new VertxOptions().setBlockedThreadCheckInterval(1000)
                                                  .setClusterManager(mgr);
        Vertx.clusteredVertx(vOptions, res -> {
            if (res.succeeded()) {
                m_logger.info("clustered vertx");
                m_vertx = res.result();
                m_vertx.deployVerticle(new ApiVerticle(), (result) -> {
                    if (result.succeeded()) {
                        m_logger.info("deploy success");
                    } else {
                        m_logger.error("deploy failed", result.cause());
                    }
                });
            } else {
                // failed!
                m_logger.info("clustered vertx failed");
            }
        });
        
    }

    public void stop() {
        m_logger.info("Stopping Driver");
        Set<String> ids = m_vertx.deploymentIDs();
        for (String id : ids) {
            m_vertx.undeploy(id, (result) -> {
                if (result.succeeded()) {
                    
                } else {
                    
                }
            });
        }
    }
}
