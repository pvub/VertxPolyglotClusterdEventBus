package com.pvub.vertxclusteredeventbus;

import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

/**
 * A sample API which communicates with other nodes in the cluster
 * @author Udai
 */
public class ApiVerticle extends AbstractVerticle {
    private final Logger    m_logger;
    private Router          m_router;
    private JsonObject      m_config = null;
    private String          m_node_id = "";
    private ClusterManager  m_clusterManager = null;

    public ApiVerticle() {
        super();
        m_logger = LoggerFactory.getLogger("APIVERTICLE");
    }
    
    @Override
    public void start() throws Exception {
        if (vertx != null && vertx.isClustered()) {
            m_clusterManager = ((VertxInternal)vertx).getClusterManager();
            m_node_id = m_clusterManager.getNodeID();
            m_logger.info("Starting ApiVerticle node={}", m_node_id);
        } else {
            m_logger.info("Starting ApiVerticle");
        }
        
        String m_path_to_config = System.getProperty("client.config", "");
        if (!m_path_to_config.isEmpty()) {
            ConfigStoreOptions fileStore = new ConfigStoreOptions()
                .setType("file")
                .setFormat("json")
                .setConfig(new JsonObject().put("path", m_path_to_config));

            ConfigRetriever retriever = ConfigRetriever.create(vertx, 
                    new ConfigRetrieverOptions().addStore(fileStore));
            retriever.getConfig(
                config -> {
                    m_logger.info("config retrieved");
                    if (config.failed()) {
                        m_logger.info("No config");
                    } else {
                        m_logger.info("Got config");
                        startup(config.result());
                    }
                }
            );

            retriever.listen(change -> {
                m_logger.info("config changed");
                // Previous configuration
                JsonObject previous = change.getPreviousConfiguration();
                // New configuration
                JsonObject conf = change.getNewConfiguration();
                processConfigChange(previous, conf);
            });
        } else {
            startup(config());
        }
    }
    
    private void sendNodes() {
        if (m_clusterManager != null) {
            List<String> nodes = m_clusterManager.getNodes();
            JsonObject obj = new JsonObject();
            JsonArray arr  = new JsonArray();
            for (String node : nodes) {
                arr.add(node);
            }
            obj.put("nodes", arr);
            m_logger.info("Sending {}", obj.encode());
            vertx.eventBus().send("to.client", obj.encode());
        }
    }
    
    private void startup(JsonObject config) {
        processConfig(config);

        // Create a router object.
        m_router = Router.router(vertx);

        // Handle CORS requests.
        m_router.route().handler(CorsHandler.create("*")
            .allowedMethod(HttpMethod.GET)
            .allowedMethod(HttpMethod.OPTIONS)
            .allowedHeader("Accept")
            .allowedHeader("Authorization")
            .allowedHeader("Content-Type"));

        // set in and outbound permitted addresses
        // Allow events for the designated addresses in/out of the event bus bridge
        BridgeOptions opts = new BridgeOptions()
          .addInboundPermitted(new PermittedOptions().setAddress("to.api"))
          .addInboundPermitted(new PermittedOptions().setAddress("to.api."+m_node_id))
          .addOutboundPermitted(new PermittedOptions().setAddress("to.client"))
          .addOutboundPermitted(new PermittedOptions().setAddress(m_node_id+".to.client"));
        
        SockJSHandler sockJSHandler = SockJSHandler.create(vertx);
        sockJSHandler.bridge(opts, event -> {
          // This signals that it's ok to process the event
          event.complete(true);
        });
        m_router.route("/eventbus/*").handler(sockJSHandler);
        m_router.route("/static/*").handler(StaticHandler.create());

        int port = m_config.getInteger("port", 8080);
        // Create the HTTP server and pass the 
        // "accept" method to the request handler.
        vertx
            .createHttpServer()
            .requestHandler(m_router::accept)
            .listen(
                // Retrieve the port from the 
                // configuration, default to 8080.
                port,
                result -> {
                    if (result.succeeded()) {
                        m_logger.info("Listening now on port {}", port);
                    } else {
                        m_logger.error("Failed to listen on port {}", port, result.cause());
                    }
                }
            );

        // Register to listen for messages coming IN to the server
        vertx.eventBus().consumer("node."+m_node_id).handler(message -> {
            // Send the message back out to all clients with the timestamp prepended.
            vertx.eventBus().publish(m_node_id+".to.client", message);
        });
        vertx.eventBus().consumer("to.api."+m_node_id).handler(message -> {
            String json_request = (String) message.body();
            JsonObject json_request_obj = new JsonObject(json_request);
            String id = json_request_obj.getString("node", "");
            // Send the message back out to all clients with the timestamp prepended.
            vertx.eventBus().send("node."+id, message);
        });
        vertx.eventBus().consumer("to.api").handler(message -> {
            String json_request = (String) message.body();
            JsonObject json_request_obj = new JsonObject(json_request);
            m_logger.info("Message to.api {}", json_request);
            if (json_request_obj.containsKey("GetNodes")) {
                sendNodes();
            } else {
                vertx.eventBus().publish("to.client", json_request);
            }
        });
    }
    
    private void processConfig(JsonObject config) {
        m_config = config;
    }
    private void processConfigChange(JsonObject prev, JsonObject current) {
    }
}
