package org.restcomm.slee.container.build.as7.deployment;

import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.module.ResourceRoot;
import org.jboss.logging.Logger;
import org.jboss.vfs.TempFileProvider;
import org.jboss.vfs.VFS;
import org.jboss.vfs.VFSUtils;
import org.jboss.vfs.VirtualFile;
import org.mobicents.slee.container.deployment.ExternalDeployer;
import org.mobicents.slee.container.deployment.InternalDeployer;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class ExternalDeployerImpl implements ExternalDeployer {

	Logger log = Logger.getLogger(ExternalDeployerImpl.class);

	/**
	 * a list of URLs which the deployer was asked to deploy before it actually started
	 */
	private List<URL> waitingDeployerList = new ArrayList<URL>();
	private List<URL> waitingDependencyList = new CopyOnWriteArrayList<URL>();

	private static class DeploymentUnitRecord {
		DeploymentUnit deploymentUnit;
		SleeDeploymentMetaData deploymentMetaData;
		VirtualFile defaultRoot;
	}

	private Map<URL, DeploymentUnitRecord> records = new HashMap<URL, DeploymentUnitRecord>();

	private InternalDeployer internalDeployer;

	public ExternalDeployerImpl() {
		log.info("Mobicents SLEE External Deployer initialized.");

		Timer waitingDependencyTimer = new Timer();
		waitingDependencyTimer.schedule(new WaitingDependencyTimerTask(), 0, 2000);
	}

	private class WaitingDependencyTimerTask extends TimerTask {
		@Override
		public void run() {
			if (waitingDependencyList.size() == 0) {
				return;
			}

			// CopyOnWriteArrayList is snapshot-safe; iterate directly
			for (Iterator<URL> it = waitingDependencyList.iterator(); it.hasNext();) {
				URL deployableUnitURL = it.next();
				try {
					DeploymentUnitRecord record = records.get(deployableUnitURL);
					if (record == null) {
						waitingDependencyList.remove(deployableUnitURL);
						continue;
					}

					ResourceRoot deploymentRoot = record.deploymentUnit.getAttachment(Attachments.DEPLOYMENT_ROOT);
					VirtualFile rootFile = deploymentRoot.getRoot();

					record.deploymentMetaData.checkDependency(rootFile);
					if (record.deploymentMetaData.isDependencyItemsPassed()) {
						waitingDependencyList.remove(deployableUnitURL);
						callSubDeployer(deployableUnitURL, records.get(deployableUnitURL));
					}
				} catch (Exception e) {
					log.error("Failure during deployment procedures.", e);
				}
			}

			// cancel timer after all deployments were deployed
			if (waitingDependencyList.size() == 0) {
				cancel();
			}
		}
	}

	@Override
	public void setInternalDeployer(InternalDeployer internalDeployer) {
		// set deployer
		this.internalDeployer = internalDeployer;

		// do the deployments on waiting list
		int failCount = 0;
		for (Iterator<URL> it = this.waitingDeployerList.iterator(); it.hasNext();) {
			URL deployableUnitURL = it.next();
			it.remove();
			try {
				callSubDeployer(deployableUnitURL, records.get(deployableUnitURL));
			} catch (Exception e) {
				failCount++;
				log.error("Failure during deployment procedures.", e);
			}
		}
		if (failCount > 0) {
			if (log.isInfoEnabled()) {
				log.info("SLEE External Deployer startup: " + failCount + " DUs rejected by the SLEE Internal Deployer, due to errors.");
			}
		}
		this.waitingDeployerList.clear();
	}

	public void deploy(DeploymentUnit deploymentUnit, URL deployableUnitURL, SleeDeploymentMetaData deploymentMetaData, VirtualFile defaultRoot) {
		if (log.isTraceEnabled()) {
			log.trace("ExternalDeployerImpl 'deploy' called:");
			log.trace("DeploymentUnit..........." + deploymentUnit);
			log.trace("SleeDeploymentMetaData..." + deploymentMetaData);
		}

		if (deploymentMetaData != null) {
			String deploymentUnitName = deploymentUnit != null ?
					deploymentUnit.getName() : "default deployment: " + defaultRoot.getName();

			try {
				// create record and store it
				DeploymentUnitRecord record = new DeploymentUnitRecord();
				record.deploymentMetaData = deploymentMetaData;
				record.deploymentUnit = deploymentUnit;
				record.defaultRoot = defaultRoot;
				records.put(deployableUnitURL, record);

				// deploy if possible
				if (!deploymentMetaData.isDependencyItemsPassed()) {
					log.warn("Deployment " + deploymentUnit.getName() + " is missing the preceding dependencies. Added to waiting list.");
					waitingDependencyList.add(deployableUnitURL);
				} else if (internalDeployer == null) {
					if (log.isDebugEnabled()) {
						log.debug("Unable to INSTALL " + deploymentUnitName
								+ " right now. Waiting for Container to start.");
						log.debug("deployableUnitURL: " + deployableUnitURL);
					}
					waitingDeployerList.add(deployableUnitURL);
				} else {
					callSubDeployer(deployableUnitURL, record);
				}
			} catch (Exception e) {
				log.error("Failure while deploying " + deploymentUnitName, e);
			}
		}
	}

	private void callSubDeployer(URL deployableUnitURL, DeploymentUnitRecord record) throws Exception {
		String deployableUnitName = record.deploymentUnit != null ? record.deploymentUnit.getName() : "";

		// FIX: Set TCCL to SLEE container classloader so DU classloader domains
		// can resolve javax.slee.* classes provided by the container module
		ClassLoader oldTCCL = Thread.currentThread().getContextClassLoader();
		try {
			if (internalDeployer != null) {
				ClassLoader sleeClassLoader = internalDeployer.getClass().getClassLoader();
				Thread.currentThread().setContextClassLoader(sleeClassLoader);
			}

		internalDeployer.accepts(deployableUnitURL, deployableUnitName);
		internalDeployer.init(deployableUnitURL, deployableUnitName);

		VirtualFile duFile = null;
		if (record.deploymentUnit != null) {
			duFile = record.deploymentUnit.getAttachment(Attachments.DEPLOYMENT_ROOT).getRoot();
		} else
		if (record.defaultRoot != null) {
			duFile = record.defaultRoot;
		}

		// Extract all nested jars to a temp directory so JarFile can open them
		java.io.File duTempDir = new java.io.File(System.getProperty("java.io.tmpdir"),
				"slee-du-" + Math.abs(deployableUnitName.hashCode()));
		duTempDir.mkdirs();

		VirtualFile componentFile;
		URL componentURL;
		for (String componentJar : record.deploymentMetaData.duContents) {
			try {
				// Navigate nested path manually since resolve() is not available
				String[] parts = componentJar.split("/");
				componentFile = duFile;
				for (String part : parts) {
					componentFile = componentFile.getChild(part);
				}
				// Only extract real jars; service-xml and other entries pass through as VFS URLs
				if (componentJar.endsWith(".jar")) {
					String jarName = componentJar.substring(componentJar.lastIndexOf('/') + 1);
					java.io.File tempFile = new java.io.File(duTempDir, jarName);
					if (!tempFile.exists()) {
						try (java.io.InputStream in = componentFile.openStream();
							 java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {
							byte[] buf = new byte[8192];
							int n;
							while ((n = in.read(buf)) > 0) {
								out.write(buf, 0, n);
							}
						}
						tempFile.deleteOnExit();
					}
					componentURL = tempFile.toURI().toURL();
				} else {
					componentURL = VFSUtils.getVirtualURL(componentFile);
				}
			} catch (java.io.FileNotFoundException e) {
				log.warn("Component " + componentJar + " referenced in deployable-unit.xml but not present in DU. Skipping.");
				continue;
			} catch (Exception e) {
				throw new IllegalArgumentException("Failed to locate "
						+ componentJar + " in DU. Does it exists?", e);
			}

			try {
				internalDeployer.accepts(componentURL, "");
				internalDeployer.init(componentURL, "");
				internalDeployer.start(componentURL, "");
			} catch (Exception e) {
				log.error("Failed to deploy component " + componentJar, e);
				throw e;
			}
		}
		internalDeployer.start(deployableUnitURL, deployableUnitName);
		} finally {
			Thread.currentThread().setContextClassLoader(oldTCCL);
		}
	}

	public void undeploy(DeploymentUnit deploymentUnit, URL deployableUnitURL, SleeDeploymentMetaData deploymentMetaData) {
		if (log.isTraceEnabled()) {
			log.trace("ExternalDeployerImpl 'undeploy' called:");
			log.trace("DeploymentUnit..........." + deploymentUnit);
			log.trace("SleeDeploymentMetaData..." + deploymentMetaData);
		}
		
		if (deployableUnitURL != null) {
			records.remove(deployableUnitURL);
			if (internalDeployer != null) {
				String deployableUnitName = deploymentUnit != null ? deploymentUnit.getName() : "";
				try {
					internalDeployer.stop(deployableUnitURL, deployableUnitName);
				} catch (Exception e) {
					log.error(
						"Failure while undeploying " + deployableUnitName, e);
				}
			}
		}
	}

}