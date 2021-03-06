package hudson.plugins.ec2.ssh;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;
import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.KeyPairInfo;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import org.apache.commons.io.IOUtils;
import org.jets3t.service.S3ServiceException;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.lang.Throwable;

/**
 * {@link ComputerLauncher} that connects to a Unix slave on EC2 by using SSH.
 * 
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends EC2ComputerLauncher {

    private final int FAILED=-1;
    private final int SAMEUSER=0;
    private final int RECONNECT=-2;

    protected void launch(EC2Computer computer, PrintStream logger, Instance inst) throws IOException, EC2Exception, InterruptedException, S3ServiceException {

        final Connection bootstrapConn;
        final Connection conn;
        Connection cleanupConn = null; // java's code path analysis for final doesn't work that well.
        boolean successful = false;
        
        try {
            bootstrapConn = connectToSsh(computer, logger);
            int bootstrapResult = bootstrap(bootstrapConn, computer, logger);
            if (bootstrapResult == FAILED)
                return; // bootstrap closed for us.
            else if (bootstrapResult == SAMEUSER)
                cleanupConn = bootstrapConn; // take over the connection
            else {
                // connect fresh as ROOT
                cleanupConn = connectToSsh(computer, logger);
                KeyPairInfo key = EC2Cloud.get().getKeyPair();
                if (!cleanupConn.authenticateWithPublicKey("root", key.getKeyMaterial().toCharArray(), "")) {
                    logger.println("Authentication failed");
                    return; // failed to connect as root.
                }
            }
            conn = cleanupConn;

            SCPClient scp = conn.createSCPClient();
            String initScript = computer.getNode().initScript;

            if(initScript!=null && initScript.trim().length()>0 && runCommand(conn, "test -e /.hudson-run-init", logger) !=0) {
                logger.println("Executing init script");
                scp.put(initScript.getBytes("UTF-8"),"init.sh","/tmp","0700");
                Session sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(computer.getRootCommandPrefix() + "/tmp/init.sh");

                sess.getStdin().close();    // nothing to write here
                sess.getStderr().close();   // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(),logger);

                int exitStatus = waitCompletion(sess);
                if (exitStatus!=0) {
                    logger.println("init script failed: exit code="+exitStatus);
                    return;
                }

                // leave the completion marker
                scp.put(new byte[0],".hudson-run-init","/","0600");

            }

            // TODO: parse the version number. maven-enforcer-plugin might help
            logger.println("Verifying that java exists");
            if( runCommand(conn, "java -fullversion", logger) !=0) {
                logger.println("Installing Java");

                String jdk = "java1.6.0_12";
                String path = "/hudson-ci/jdk/linux-i586/" + jdk + ".tgz";

                URL url = EC2Cloud.get().buildPresignedURL(path);
                String javaInstallCmd = "wget -nv -O /usr/" + jdk + ".tgz '" + url + "'" +
                  " && " + "tar xz -C /usr -f /usr/" + jdk + ".tgz" + " && " +
                  "ln -s /usr/" + jdk + "/bin/java /bin/java";

                if( runCommand(conn, javaInstallCmd, logger) != 0) {
                  logger.println("Unable to install Java");
                  return;
                }

            }

            // TODO: on Windows with ec2-sshd, this scp command ends up just putting slave.jar as c:\tmp
            // bug in ec2-sshd?

            logger.println("Copying slave.jar");
            scp.put(Hudson.getInstance().getJnlpJars("slave.jar").readFully(),
                    "slave.jar","/tmp");

            logger.println("Launching slave agent");
            final Session sess = conn.openSession();
            String jvmOpts = computer.getNode().jvmopts;
            if( jvmOpts == null) jvmOpts = "";
            logger.println("Running java " + jvmOpts + " -jar /tmp/slave.jar");
            sess.execCommand("java " + jvmOpts + " -jar /tmp/slave.jar");
            computer.setChannel(sess.getStdout(),sess.getStdin(),logger,new Listener() {
                public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });
            successful = true;
        } catch(Throwable t) {
          logger.println("Something terrible happened. I caught a " + t.getClass().getName() + " that said: " + t.getMessage());
        } finally {
            if(cleanupConn != null && !successful) {
              logger.println("Closing SSH connection...");
              cleanupConn.close();
            }
            logger.println("Session Terminated.");
        }
    }

    private int runCommand(Connection connection, String command, PrintStream logger) throws IOException, InterruptedException {
        Session session = connection.openSession();
        logger.println("Running cmd: " + command);
        session.execCommand(command);
        session.getStdin().close();    // nothing to write here
        IOUtils.copy(session.getStdout(),logger);
        IOUtils.copy(session.getStderr(),logger);

        return waitCompletion(session);
    }

    private int bootstrap(Connection bootstrapConn, EC2Computer computer, PrintStream logger) throws IOException, InterruptedException, EC2Exception {
        boolean closeBootstrap = true;
        try {
            int tries = 20;
            boolean isAuthenticated = false;
            KeyPairInfo key = EC2Cloud.get().getKeyPair();
            while (tries-- > 0) {
                logger.println("Authenticating as " + computer.getRemoteAdmin());
                isAuthenticated = bootstrapConn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getKeyMaterial().toCharArray(), "");
                if (isAuthenticated) {
                    break;
                }
                logger.println("Authentication failed. Trying again...");
                Thread.currentThread().sleep(10000);
            }
            if (!isAuthenticated) {
                logger.println("Authentication failed");
                return FAILED;
            }
            if (!computer.getRemoteAdmin().equals("root")) {
                // Get root working, so we can scp in etc.
                Session sess = bootstrapConn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(computer.getRootCommandPrefix() + "cp ~/.ssh/authorized_keys /root/.ssh/");
                sess.getStdin().close(); // nothing to write here
                sess.getStderr().close(); // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(), logger);
                int exitStatus = waitCompletion(sess);
                if (exitStatus != 0) {
                    logger.println("init script failed: exit code=" + exitStatus);
                    return FAILED;
                }
                return RECONNECT;
            } else {
                closeBootstrap = false;
                return SAMEUSER;
            }
        } finally {
            if (closeBootstrap)
                bootstrapConn.close();
        }
    }

    private Connection connectToSsh(EC2Computer computer, PrintStream logger) throws EC2Exception, InterruptedException {
        while(true) {
            try {
                String host = computer.updateInstanceDescription().getPrivateIpAddress();
                if ("0.0.0.0".equals(host)) {
                    logger.println("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }
                int port = computer.getSshPort();
                logger.println("Connecting to " + host + " on port " + port + ". ");
                Connection conn = new Connection(host, port);
                // currently OpenSolaris offers no way of verifying the host certificate, so just accept it blindly,
                // hoping that no man-in-the-middle attack is going on.
                conn.connect(new ServerHostKeyVerifier() {
                    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
                        return true;
                    }
                });
                logger.println("Connected via SSH.");
                return conn; // successfully connected
            } catch (IOException e) {
                // keep retrying until SSH comes up
                logger.println("Waiting for SSH to come up. Sleeping 5.");
                Thread.sleep(5000);
            }
        }
    }

    private int waitCompletion(Session session) throws InterruptedException {
        // I noticed that the exit status delivery often gets delayed. Wait up to 1 sec.
        for( int i=0; i<10; i++ ) {
            Integer r = session.getExitStatus();
            if(r!=null) return r;
            Thread.sleep(100);
        }
        return -1;
    }

    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
