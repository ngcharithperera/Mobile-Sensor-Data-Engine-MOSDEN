package au.csiro.gsnlite.http.rest;



import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;

import au.csiro.gsnlite.Main;
import au.csiro.gsnlite.beans.ContainerConfig;
import au.csiro.gsnlite.beans.DataField;
import au.csiro.gsnlite.beans.StreamElement;
import au.csiro.gsnlite.utils.Logger;
import au.csiro.gsnlite.wrappers.AbstractWrapper;

import com.thoughtworks.xstream.XStream;

public class PushRemoteWrapper extends AbstractWrapper {

    private static final int KEEP_ALIVE_PERIOD = 5000;

    private String TAG = "(PushRemoteWrapper.class)";
    private final transient Logger logger = Logger.getInstance();

    private final XStream XSTREAM = StreamElement4Rest.getXstream();

    private double uid = -1; //only set for push based delivery(default)

    private RemoteWrapperParamParser initParams;

    private DefaultHttpClient httpclient = new DefaultHttpClient();

    private long lastReceivedTimestamp;

    private DataField[] structure;

    List<NameValuePair> postParameters;

    public void dispose() {
        NotificationRegistry.getInstance().removeNotification(uid);
    }

    public boolean initialize() {

        try {
            initParams = new RemoteWrapperParamParser(getActiveAddressBean(), true);
            uid = Math.random();

            postParameters = new ArrayList<NameValuePair>();
            postParameters.add(new BasicNameValuePair(PushDelivery.NOTIFICATION_ID_KEY, Double.toString(uid)));
            postParameters.add(new BasicNameValuePair(PushDelivery.LOCAL_CONTACT_POINT, initParams.getLocalContactPoint()));
            // Init the http client
            if (initParams.isSSLRequired()) {
                KeyStore trustStore  = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(new FileInputStream(new File("conf/servertestkeystore")), Main.getContainerConfig().getSSLKeyStorePassword().toCharArray());
                SSLSocketFactory socketFactory = new SSLSocketFactory(trustStore);
                socketFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
                int sslPort = Main.getContainerConfig().getSSLPort() > 0 ? Main.getContainerConfig().getSSLPort() : ContainerConfig.DEFAULT_SSL_PORT;
                Scheme sch = new Scheme("https", socketFactory, sslPort);
                httpclient.getConnectionManager().getSchemeRegistry().register(sch);
            }
            Scheme plainsch = new Scheme("http", PlainSocketFactory.getSocketFactory(), Main.getContainerConfig().getContainerPort());
            httpclient.getConnectionManager().getSchemeRegistry().register(plainsch);
            //
            lastReceivedTimestamp = initParams.getStartTime();
            structure = registerAndGetStructure();
        } catch (Exception e) {
            logger.error(TAG, e.getMessage(), e);
            NotificationRegistry.getInstance().removeNotification(uid);
            return false;
        }


        return true;
    }

    public DataField[] getOutputFormat() {
        return structure;
    }

    public String getWrapperName() {
        return "Push-Remote Wrapper";
    }

    public DataField[] registerAndGetStructure() throws IOException, ClassNotFoundException {
        // Create the POST request
        HttpPost httpPost = new HttpPost(initParams.getRemoteContactPointEncoded(lastReceivedTimestamp));
        // Add the POST parameters
        httpPost.setEntity(new UrlEncodedFormEntity(postParameters, HTTP.UTF_8));
        //
        httpPost.getParams().setParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, Boolean.FALSE);
        // Create local execution context
        HttpContext localContext = new BasicHttpContext();
        //
        NotificationRegistry.getInstance().addNotification(uid, this);
        int tries = 0;
        AuthState authState = null;
        //
        while (tries < 2) {
            tries++;
            HttpResponse response = null;
            try {
                // Execute the POST request
                response = httpclient.execute(httpPost, localContext);
                //
                int sc = response.getStatusLine().getStatusCode();
                //
                if (sc == HttpStatus.SC_OK) {
                    logger.debug(TAG, new StringBuilder().append("Wants to consume the structure packet from ").append(initParams.getRemoteContactPoint()).toString());
                    structure = (DataField[]) XSTREAM.fromXML(response.getEntity().getContent());
                    logger.debug(TAG, "Connection established for: " + initParams.getRemoteContactPoint());
                    break;
                } else {
                    if (sc == HttpStatus.SC_UNAUTHORIZED)
                        authState = (AuthState) localContext.getAttribute(ClientContext.TARGET_AUTH_STATE); // Target host authentication required
                    else if (sc == HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED)
                        authState = (AuthState) localContext.getAttribute(ClientContext.PROXY_AUTH_STATE); // Proxy authentication required
                    else {
                        logger.error(TAG, new StringBuilder()
                                .append("Unexpected POST status code returned: ")
                                .append(sc)
                                .append("\nreason: ")
                                .append(response.getStatusLine().getReasonPhrase()).toString());
                    }
                    if (authState != null) {
                        if (initParams.getUsername() == null || (tries > 1 && initParams.getUsername() != null)) {
                            logger.error(TAG, "A valid username/password required to connect to the remote host: " + initParams.getRemoteContactPoint());
                        } else {
                            
                            AuthScope authScope = authState.getAuthScope();
                            logger.warn(TAG, new StringBuilder().append("Setting Credentials for host: ").append(authScope.getHost()).append(":").append(authScope.getPort()).toString());
                            Credentials creds = new UsernamePasswordCredentials(initParams.getUsername(), initParams.getPassword());
                            httpclient.getCredentialsProvider().setCredentials(authScope, creds);
                        }
                    }
                }
            }
            catch (RuntimeException ex) {
                // In case of an unexpected exception you may want to abort
                // the HTTP request in order to shut down the underlying
                // connection and release it back to the connection manager.
                logger.warn(TAG, "Aborting the HTTP POST request.");
                httpPost.abort();
                throw ex;
            }
            finally {
                if (response != null && response.getEntity() != null) {
                    response.getEntity().consumeContent();
                }
            }
        }
        
        if (structure == null)
            throw new RuntimeException("Cannot connect to the remote host.");

        return structure;
    }

    public boolean manualDataInsertion(String Xstream4Rest) {
        logger.debug(TAG, new StringBuilder().append("Received Stream Element at the push wrapper.").toString());
        StreamElement4Rest se = (StreamElement4Rest) XSTREAM.fromXML(Xstream4Rest);
        StreamElement streamElement = se.toStreamElement();

        try {
            // If the stream element is out of order, we accept the stream element and wait for the next (update the last received time and return true)
            if (isOutOfOrder(streamElement)) {
                lastReceivedTimestamp = streamElement.getTimeStamp();
                return true;
            }
            // Otherwise, we first try to insert the stream element.
            // If the stream element was inserted succesfully, we wait for the next,
            // otherwise, we return false.
            boolean status = false;
			try {
				status = postStreamElement(streamElement);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
            if (status)
                lastReceivedTimestamp = streamElement.getTimeStamp();
            return status;
        }
        catch (SQLException e) {
            logger.warn(TAG, e.getMessage(), e);
            return false;
        }
    }

    public void run() {
        HttpPost httpPost = new HttpPost(initParams.getRemoteContactPointEncoded(lastReceivedTimestamp));
        //
        httpPost.getParams().setParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, Boolean.FALSE);
        //
        HttpResponse response = null; //This is acting as keep alive.
        //
        while (isActive()) {
            try {
                Thread.sleep(KEEP_ALIVE_PERIOD);
                httpPost.setEntity(new UrlEncodedFormEntity(postParameters, HTTP.UTF_8));
                response = null;
                response = httpclient.execute(httpPost);
                int status = response.getStatusLine().getStatusCode();
                if (status != RestStreamHanlder.SUCCESS_200) {
                    logger.error(TAG, "Cant register to the remote client, retrying in:" + (KEEP_ALIVE_PERIOD / 1000) + " seconds.");
                    structure = registerAndGetStructure();
                }
            } catch (Exception e) {
                logger.warn(TAG, e.getMessage(), e);
            } finally {
                if (response != null) {
                    try {
                        response.getEntity().getContent().close();
                    } catch (Exception e) {
                        logger.warn(TAG, e.getMessage(), e);
                    }
                }
            }
        }
    }
}
