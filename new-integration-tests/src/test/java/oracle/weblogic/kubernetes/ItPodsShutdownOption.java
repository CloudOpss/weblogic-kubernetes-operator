// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1SecretReference;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.ManagedServer;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.domain.Shutdown;
import oracle.weblogic.kubernetes.actions.TestActions;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WLS_DOMAIN_TYPE;
import static oracle.weblogic.kubernetes.actions.TestActions.createConfigMap;
import static oracle.weblogic.kubernetes.actions.TestActions.patchDomainCustomResource;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReady;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDockerRegistrySecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.dockerLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.apache.commons.io.FileUtils.deleteDirectory;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test is to verify shutdown rules when shutdown properties are defined at different levels
 * (domain, cluster, adminServer and managedServer level).
 */
@DisplayName("Verify shutdown rules when shutdown properties are defined at different levels")
@IntegrationTest
class ItPodsShutdownOption {

  private static String domainNamespace = null;

  // domain constants
  private static final String domainUid = "domain1";
  private static final String adminServerName = "admin-server";
  private static final String clusterName = "cluster-1";
  private static final int replicaCount = 2;
  private static final String adminServerPodName = domainUid + "-" + ADMIN_SERVER_NAME_BASE;
  private static final String managedServerPodNamePrefix = domainUid + "-" + MANAGED_SERVER_NAME_BASE;
  private static final String indManagedServerName1 = "independent-ms-1";
  private static final String indManagedServerName2 = "independent-ms-2";
  private static LoggingFacade logger = null;

  private static String miiImage;
  private static String adminSecretName;
  private static String encryptionSecretName;

  /**
   * Get namespaces for operator and WebLogic domain.
   *
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   *                   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(2) List<String> namespaces) {
    logger = getLogger();
    // get a unique operator namespace
    logger.info("Getting a unique namespace for operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    String opNamespace = namespaces.get(0);

    // get a unique domain namespace
    logger.info("Getting a unique namespace for WebLogic domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domainNamespace = namespaces.get(1);

    // install and verify operator
    installAndVerifyOperator(opNamespace, domainNamespace);

    // get the pre-built image created by IntegrationTestWatcher
    miiImage = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;

    // docker login and push image to docker registry if necessary
    dockerLoginAndPushImageToRegistry(miiImage);

    // create docker registry secret to pull the image from registry
    logger.info("Creating docker registry secret in namespace {0}", domainNamespace);
    createDockerRegistrySecret(domainNamespace);

    // create secret for admin credentials
    logger.info("Creating secret for admin credentials");
    adminSecretName = "weblogic-credentials";
    createSecretWithUsernamePassword(adminSecretName, domainNamespace, "weblogic", "welcome1");

    // create encryption secret
    logger.info("Creating encryption secret");
    encryptionSecretName = "encryptionsecret";
    createSecretWithUsernamePassword(encryptionSecretName, domainNamespace, "weblogicenc", "weblogicenc");

  }

  /**
   * This test is to verify different shutdown options specified at different scopes in Domain Resource Definition.
   * Refer to section "Shutdown options" of Documentation link:
   * https://oracle.github.io/weblogic-kubernetes-operator/userguide/managing-domains/domain-lifecycle/startup/
   * step 1: Startup a WebLogic domain with one cluster that initially has one running managed server. The shutdown
   * option is configured as follows:
   * domain: SHUTDOWN_TYPE -> Graceful.
   * adminServer: SHUTDOWN_TYPE -> Forced.
   *              SHUTDOWN_IGNORE_SESSIONS -> true.
   *              SHUTDOWN_TIMEOUT -> 40.
   * cluster: SHUTDOWN_IGNORE_SESSIONS -> true.
   *          SHUTDOWN_TIMEOUT -> 80.
   * managedServer1: SHUTDOWN_TIMEOUT -> 100.
   * step2: Scale cluster with two managed servers.
   * step 3: Verify shutdown properties of admin server, managedServer1 and newly scaled up managedServer2.
   * Domain level "Graceful" SHUTDOWN_TYPE overrides server level setting and is used for all servers.
   * So adminServer has "Graceful" SHUTDOWN_TYPE, default "false" SHUTDOWN_IGNORE_SESSIONS and
   * configured "40" SHUTDOWN_TIMEOUT.
   * Managed level setting overrides cluster level setting so managedServer1 has configured "100"
   * SHUTDOWN_TIMEOUT, cluster level "true" SHUTDOWN_IGNORE_SESSIONS  "true" and "Graceful" SHUTDOWN_TYPE.
   * Newly scaled up managedServer2 takes setting from combination of domain level and cluster level
   * with "Graceful" SHUTDOWN_TYPE, "true" SHUTDOWN_IGNORE_SESSIONS and default "30" SHUTDOWN_TIMEOUT
   */
  @Test
  @DisplayName("Verify shutdown rules when shutdown properties are defined at different levels ")
  public void testShutdownProps() throws ApiException {


    // create a basic model in image domain
    Shutdown[] shutDownObjects = new Shutdown[3];
    Shutdown admin = new Shutdown().ignoreSessions(Boolean.TRUE).shutdownType("Forced").timeoutSeconds(40L);
    Shutdown cluster = new Shutdown().ignoreSessions(Boolean.FALSE).shutdownType("Graceful").timeoutSeconds(60L);
    Shutdown ms1 = new Shutdown().ignoreSessions(Boolean.FALSE).shutdownType("Graceful").timeoutSeconds(120L);
    Shutdown ms2 = new Shutdown().ignoreSessions(Boolean.TRUE).shutdownType("Forced").timeoutSeconds(45L);
    shutDownObjects[0] = admin;
    shutDownObjects[1] = cluster;
    shutDownObjects[2] = ms1;
    shutDownObjects[3] = ms2;
    Domain domain = buildDomainResource(shutDownObjects);
    createVerifyDomain(domain);

    String yamlString = "topology:\n"
        + "  Server:\n"
        + "    'independent-ms-1':\n"
        + "      ListenPort: '8001'\n"
        + "    'independent-ms-2':\n"
        + "      ListenPort: '8001'\n"
        + "    'config-server3':\n"
        + "      Cluster: 'ConfigCluster'\n"
        + "      ListenPort: '8001'";

    String cmName = "configured.cluster";
    createClusterConfigMap(cmName, yamlString);
    StringBuffer patchStr = null;
    patchStr = new StringBuffer("[{");
    patchStr.append("\"op\": \"add\",")
        .append(" \"path\": \"/spec/configuration/model/configMap\",")
        .append(" \"value\":  \"" + cmName + "\"")
        .append(" }]");
    logger.log(Level.INFO, "Configmap patch string: {0}", patchStr);

    V1Patch patch = new V1Patch(new String(patchStr));
    boolean cmPatched = assertDoesNotThrow(()
        -> patchDomainCustomResource(domainUid, domainNamespace, patch, "application/json-patch+json"),
        "patchDomainCustomResource(configMap)  failed ");
    assertTrue(cmPatched, "patchDomainCustomResource(configMap) failed");


    verifyServerLog(domainNamespace, adminServerPodName,
        new String[]{"SHUTDOWN_IGNORE_SESSIONS=true", "SHUTDOWN_TYPE=Forced", "SHUTDOWN_TIMEOUT=40"});
    verifyServerLog(domainNamespace, managedServerPodNamePrefix + 1,
        new String[]{"SHUTDOWN_IGNORE_SESSIONS=false", "SHUTDOWN_TYPE=Graceful", "SHUTDOWN_TIMEOUT=60"});
    verifyServerLog(domainNamespace, managedServerPodNamePrefix + 2,
        new String[]{"SHUTDOWN_IGNORE_SESSIONS=false", "SHUTDOWN_TYPE=Graceful", "SHUTDOWN_TIMEOUT=60"});


    assertTrue(verifyServerShutdownProp(adminServerPodName, domainNamespace, "Graceful", "40", "false"));
    assertTrue(verifyServerShutdownProp(managedServerPodNamePrefix + 1, domainNamespace, "Graceful", "100", "true"));
    assertTrue(verifyServerShutdownProp(managedServerPodNamePrefix + 2, domainNamespace, "Graceful", "30", "true"));
    try {
      TimeUnit.MINUTES.sleep(30);
    } catch (InterruptedException ex) {
      //
    }
  }


  private Domain buildDomainResource(Shutdown[] shutDownObject) {

    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domainNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(REPO_SECRET_NAME))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domainNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom "))
                .addEnvItem(new V1EnvVar()
                    .name("SHUTDOWN_TYPE")
                    .value("Graceful")))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                .serverPod(new ServerPod()
                    .shutdown(shutDownObject[0])))
            .addClustersItem(new Cluster()
                .clusterName(clusterName)
                .replicas(replicaCount)
                .serverStartState("RUNNING")
                .serverPod(new ServerPod()
                    .shutdown(shutDownObject[1])))
            .configuration(new Configuration()
                .model(new Model()
                    .domainType(WLS_DOMAIN_TYPE)
                    .runtimeEncryptionSecret(encryptionSecretName)))
            .addManagedServersItem(new ManagedServer()
                .serverStartPolicy("Always")
                .serverName(indManagedServerName1)
                .serverPod(new ServerPod()
                    .shutdown(shutDownObject[2])))
            .addManagedServersItem(new ManagedServer()
                .serverStartPolicy("Always")
                .serverName(indManagedServerName2)
                .serverPod(new ServerPod()
                    .shutdown(shutDownObject[3]))));
    return domain;
  }

  private void createVerifyDomain(Domain domain) {
    // create model in image domain
    logger.info("Creating model in image domain {0} in namespace {1} using docker image {2}",
        domainUid, domainNamespace, miiImage);
    createDomainAndVerify(domain, domainNamespace);

    // check that admin server pod exists in the domain namespace
    logger.info("Checking that admin server pod {0} exists in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodExists(adminServerPodName, domainUid, domainNamespace);

    // check that admin server pod is ready
    logger.info("Checking that admin server pod {0} is ready in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReady(adminServerPodName, domainUid, domainNamespace);

    // check that admin service exists in the domain namespace
    logger.info("Checking that admin service {0} exists in namespace {1}",
        adminServerPodName, domainNamespace);
    checkServiceExists(adminServerPodName, domainNamespace);

    // check for managed server pods existence in the domain namespace
    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = managedServerPodNamePrefix + i;

      // check that the managed server pod exists in the domain namespace
      logger.info("Checking that managed server pod {0} exists in namespace {1}",
          managedServerPodName, domainNamespace);
      checkPodExists(managedServerPodName, domainUid, domainNamespace);

      // check that the managed server pod is ready
      logger.info("Checking that managed server pod {0} is ready in namespace {1}",
          managedServerPodName, domainNamespace);
      checkPodReady(managedServerPodName, domainUid, domainNamespace);

      // check that the managed server service exists in the domain namespace
      logger.info("Checking that managed server service {0} exists in namespace {1}",
          managedServerPodName, domainNamespace);
      checkServiceExists(managedServerPodName, domainNamespace);
    }

  }

  /**
   * Verify the server pod Shutdown properties.
   * @param podName the name of the server pod
   * @param domainNS the namespace where the server pod exist
   * @param props the shutdown properties
   */
  private static boolean verifyServerShutdownProp(
      String podName,
      String domainNS,
      String... props) throws io.kubernetes.client.openapi.ApiException {

    V1Pod serverPod = Kubernetes.getPod(domainNS, null, podName);
    assertNotNull(serverPod,"The server pod does not exist in namespace " + domainNS);
    List<V1EnvVar> envVars = Objects.requireNonNull(serverPod.getSpec()).getContainers().get(0).getEnv();

    boolean found = false;
    HashMap<String, Boolean> propFound = new HashMap<String, Boolean>();
    for (String prop : props) {
      for (var envVar : envVars) {
        if (envVar.getName().contains("SHUTDOWN")) {
          if (envVar.getValue() != null && envVar.getValue().contains(prop)) {
            logger.info("For pod {0} SHUTDOWN option {1} has value {2} ",
                podName, envVar.getName(),  envVar.getValue());
            logger.info("Property with value " + prop + " has found");
            propFound.put(prop, true);
          }
        }
      }
    }
    if (props.length == propFound.size()) {
      found = true;
    }
    return found;
  }

  private void verifyServerLog(String serverName, String[] envVars) {
    String logDir = "/u01/domains/" + domainUid + "/servers/" + serverName + "/logs";
    assertDoesNotThrow(() -> {
      Path destLogDir = Files.createDirectories(Paths.get(
          TestConstants.RESULTS_ROOT, this.getClass().getSimpleName(), serverName));
      deleteDirectory(destLogDir.toFile());
      Files.createDirectories(destLogDir);
      Kubernetes.copyDirectoryFromPod(TestActions.getPod(domainNamespace, null, domainUid + "-" + serverName),
          Paths.get(logDir).toString(), destLogDir);
      for (String envVar : envVars) {
        assertTrue(Files.readString(
            Paths.get(destLogDir.toString(), logDir, serverName + ".out"))
            .contains(envVar));
      }
    });
  }

  private void verifyServerLog(String namespace, String podName, String[] envVars) throws ApiException {
    String podLog = TestActions.getPodLog(podName, namespace);
    for (String envVar : envVars) {
      assertTrue(podLog.contains(envVar));
    }
  }

  // Crate a ConfigMap with a model file to add a new WebLogic cluster
  private void createClusterConfigMap(String configMapName, String model) {
    Map<String, String> labels = new HashMap<>();
    labels.put("weblogic.domainUid", domainUid);
    Map<String, String> data = new HashMap<>();
    data.put(configMapName, model);

    V1ObjectMeta meta = new V1ObjectMeta()
        .labels(labels)
        .name(configMapName)
        .namespace(domainNamespace);
    V1ConfigMap configMap = new V1ConfigMap()
        .data(data)
        .metadata(meta);

    boolean cmCreated = assertDoesNotThrow(() -> createConfigMap(configMap),
        String.format("Can't create ConfigMap %s", configMapName));
    assertTrue(cmCreated, String.format("createConfigMap failed while creating ConfigMap %s", configMapName));
  }


}

