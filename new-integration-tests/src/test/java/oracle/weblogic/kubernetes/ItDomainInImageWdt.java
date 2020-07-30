// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSecurityContext;
import io.kubernetes.client.openapi.models.V1SecretReference;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.annotations.tags.Slow;
import oracle.weblogic.kubernetes.assertions.TestAssertions;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import org.awaitility.core.ConditionFactory;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WDT_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WDT_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.actions.TestActions.createDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.getDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.getPodCreationTimestamp;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.domainExists;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.verifyRollingRestartOccurred;
import static oracle.weblogic.kubernetes.utils.CommonPatchTestUtils.patchDomainResource;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReady;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDockerRegistrySecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

//import org.junit.jupiter.api.AfterAll;

// Test to create model in image domain and verify the domain started successfully
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Test to create domain in image domain using wdt and start the domain")
@IntegrationTest
class ItDomainInImageWdt {

  private static String opNamespace = null;
  private static String domainNamespace = null;
  private static ConditionFactory withStandardRetryPolicy = null;
  //private Domain domain1 = null;
  private static LoggingFacade logger = null;
  Map<String, DateTime> podsWithTimeStamps = null;

  private static final String domainUid = "domain1";
  private static final String clusterName = "cluster-1";
  private static final int replicaCount = 2;
  private static final String adminServerPodName = domainUid + "-" + ADMIN_SERVER_NAME_BASE;
  private static final String managedServerPrefix = domainUid + "-" + MANAGED_SERVER_NAME_BASE;
  private boolean previousTestSuccessful = false;

  /**
   * Install Operator.
   *
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   *                   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(2) List<String> namespaces) {
    logger = getLogger();
    // create standard, reusable retry/backoff policy
    withStandardRetryPolicy = with().pollDelay(2, SECONDS)
        .and().with().pollInterval(10, SECONDS)
        .atMost(5, MINUTES).await();

    // get a new unique opNamespace
    logger.info("Creating unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    logger.info("Creating unique namespace for Domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domainNamespace = namespaces.get(1);

    // install and verify operator
    installAndVerifyOperator(opNamespace, domainNamespace);

  }

  /**
   * create a domain in image domain and verify domain creation by checking pod
   * ready/running and service exists.
   */
  @Test
  @Order(1)
  @DisplayName("Create domain in image domain using WDT")
  @Slow
  public void testCreateDomaininImageWdt() {
    previousTestSuccessful = false;
    // admin/managed server name here should match with model yaml in WDT_MODEL_FILE
    final String adminServerPodName = domainUid + "-admin-server";
    final String managedServerPrefix = domainUid + "-managed-server";
    final int replicaCount = 2;

    // Create the repo secret to pull the image
    createDockerRegistrySecret(domainNamespace);

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    createSecretWithUsernamePassword(adminSecretName, domainNamespace, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    // create the domain CR
    createDomainResource(domainUid, domainNamespace, adminSecretName, REPO_SECRET_NAME,
        replicaCount);

    // wait for the domain to exist
    logger.info("Check for domain custom resource in namespace {0}", domainNamespace);
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for domain {0} to be created in namespace {1} "
                    + "(elapsed time {2}ms, remaining time {3}ms)",
                domainUid,
                domainNamespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(domainExists(domainUid, DOMAIN_VERSION, domainNamespace));


    // check admin server pod exists
    logger.info("Check for admin server pod {0} existence in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodExists(adminServerPodName, domainUid, domainNamespace);

    // check managed server pods exist
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check for managed server pod {0} existence in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkPodExists(managedServerPrefix + i, domainUid, domainNamespace);
    }

    // check admin server pod is ready
    logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReady(adminServerPodName, domainUid, domainNamespace);

    // check managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed server pod {0} to be ready in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkPodReady(managedServerPrefix + i, domainUid, domainNamespace);
    }

    logger.info("Check admin service {0} is created in namespace {1}",
        adminServerPodName, domainNamespace);
    checkServiceExists(adminServerPodName, domainNamespace);

    // check managed server services created
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check managed server service {0} is created in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkServiceExists(managedServerPrefix + i, domainNamespace);
    }

    logger.info("Getting node port");
    int serviceNodePort = assertDoesNotThrow(() -> getServiceNodePort(domainNamespace, adminServerPodName
            + "-external", "default"),
        "Getting admin server node port failed");

    logger.info("Validating WebLogic admin server access by login to console");
    boolean loginSuccessful = assertDoesNotThrow(() -> {
      return TestAssertions.adminNodePortAccessible(serviceNodePort, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);
    }, "Access to admin server node port failed");
    assertTrue(loginSuccessful, "Console login validation failed");
    previousTestSuccessful = true;
  }

  /**
   * Modify the domain scope property on the domain resource.
   * Verify all pods are restarted and back to ready state.
   * The resources tested: includeServerOutInPodLog: true --> includeServerOutInPodLog: false.
   */
  //@Test
  @Order(2)
  @DisplayName("Verify server pods are restarted by changing IncludeServerOutInPodLog")
  @Slow
  public void testServerPodsRestartByChangingIncludeServerOutInPodLog() {
    Assumptions.assumeTrue(previousTestSuccessful);
    previousTestSuccessful = false;
    // get the original domain resource before update
    Domain domain1 = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace),
        String.format("getDomainCustomResource failed with ApiException when tried to get domain %s in namespace %s",
            domainUid, domainNamespace));
    assertNotNull(domain1, domain1 + " is null");
    assertNotNull(domain1.getSpec(), domain1 + "/spec is null");

    // get the map with server pods and their original creation timestamps
    podsWithTimeStamps = getPodsWithTimeStamps();

    //print out the original IncludeServerOutInPodLog
    Boolean includeServerOutInPodLog = domain1.getSpec().getIncludeServerOutInPodLog();
    logger.info("Original IncludeServerOutInPodLog is: {0}", includeServerOutInPodLog);

    //change includeServerOutInPodLog: true --> includeServerOutInPodLog: false
    StringBuffer patchStr = null;
    patchStr = new StringBuffer("[{");
    patchStr.append("\"op\": \"replace\",")
        .append(" \"path\": \"/spec/includeServerOutInPodLog\",")
        .append("\"value\": ")
        .append(false)
        .append("}]");
    logger.info("PatchStr for includeServerOutInPodLog: {0}", patchStr.toString());

    boolean cmPatched = patchDomainResource(domainUid, domainNamespace, patchStr);
    assertTrue(cmPatched, "patchDomainCustomResource(IncludeServerOutInPodLog) failed");

    domain1 = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace),
        String.format("getDomainCustomResource failed with ApiException when tried to get domain %s in namespace %s",
            domainUid, domainNamespace));
    assertNotNull(domain1, domain1 + " is null");
    assertNotNull(domain1.getSpec(), domain1 + "/spec is null");

    includeServerOutInPodLog = domain1.getSpec().getIncludeServerOutInPodLog();
    logger.info("In the new patched domain IncludeServerOutInPodLog is: {0}",
        includeServerOutInPodLog);
    assertFalse(includeServerOutInPodLog, "IncludeServerOutInPodLog was not updated");

    // verify the server pods are rolling restarted and back to ready state
    logger.info("Verifying rolling restart occurred for domain {0} in namespace {1}",
        domainUid, domainNamespace);
    assertTrue(assertDoesNotThrow(
        () -> verifyRollingRestartOccurred(podsWithTimeStamps, 1, domainNamespace),
        "More than one pod was restarted at same time"),
        String.format("Rolling restart failed for domain %s in namespace %s", domainUid, domainNamespace));

    previousTestSuccessful = true;
  }


  /**
   * Modify domain scope serverPod env property on the domain resource.
   * Verify all pods are restarted and back to ready state.
   * The env property tested: "-Dweblogic.StdoutDebugEnabled=false" --> "-Dweblogic.StdoutDebugEnabled=true".
   */
  //@Test
  @Order(3)
  @DisplayName("Verify server pods are restarted by changing serverPod env property")
  @Slow
  public void testServerPodsRestartByChangingEnvProperty() {
    Assumptions.assumeTrue(previousTestSuccessful);
    previousTestSuccessful = false;
    // get the original domain resource before update
    Domain domain1 = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace),
        String.format("getDomainCustomResource failed with ApiException when tried to get domain %s in namespace %s",
            domainUid, domainNamespace));
    assertNotNull(domain1, domain1 + " is null");
    assertNotNull(domain1.getSpec(), domain1 + " /spec/serverPod is null");
    assertNotNull(domain1.getSpec().getServerPod(), domain1 + " /spec/serverPod is null");
    assertNotNull(domain1.getSpec().getServerPod().getEnv(), domain1 + "/spec/serverPod/env is null");

    // get the map with server pods and their original creation timestamps
    podsWithTimeStamps = getPodsWithTimeStamps();

    //get out the original env
    List<V1EnvVar> envList = domain1.getSpec().getServerPod().getEnv();
    envList.forEach(env -> {
      logger.info("The name is: {0}, value is: {1}", env.getName(), env.getValue());
      if (env.getName().equalsIgnoreCase("JAVA_OPTIONS")
          && env.getValue().equalsIgnoreCase("-Dweblogic.StdoutDebugEnabled=false")) {
        logger.info("Change JAVA_OPTIONS to -Dweblogic.StdoutDebugEnabled=true");
        StringBuffer patchStr = null;
        patchStr = new StringBuffer("[{");
        patchStr.append("\"op\": \"replace\",")
            .append(" \"path\": \"/spec/serverPod/env/0/value\",")
            .append("\"value\": \"")
            .append("-Dweblogic.StdoutDebugEnabled=true")
            .append("\"}]");
        logger.info("PatchStr for JAVA_OPTIONS {0}", patchStr.toString());

        boolean cmPatched = patchDomainResource(domainUid, domainNamespace, patchStr);
        assertTrue(cmPatched, "patchDomainCustomResource(StdoutDebugEnabled=true) failed");
      }
    }

    );

    domain1 = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace),
        String.format("getDomainCustomResource failed with ApiException when tried to get domain %s in namespace %s",
            domainUid, domainNamespace));
    assertNotNull(domain1, domain1 + " is null");
    assertNotNull(domain1.getSpec(), domain1 + " /spec/serverPod is null");
    assertNotNull(domain1.getSpec().getServerPod(), domain1 + " /spec/serverPod is null");
    assertNotNull(domain1.getSpec().getServerPod().getEnv(), domain1 + "/spec/serverPod/env is null");

    //verify the env in the new patched domain
    envList = domain1.getSpec().getServerPod().getEnv();
    String envValue = envList.get(0).getValue();
    logger.info("In the new patched domain envValue is: {0}", envValue);
    assertTrue(envValue.equalsIgnoreCase("-Dweblogic.StdoutDebugEnabled=true"), "JAVA_OPTIONS was not updated"
        + " in the new patched domain");

    // verify the server pods are rolling restarted and back to ready state
    logger.info("Verifying rolling restart occurred for domain {0} in namespace {1}",
        domainUid, domainNamespace);
    assertTrue(assertDoesNotThrow(
        () -> verifyRollingRestartOccurred(podsWithTimeStamps, 1, domainNamespace),
        "More than one pod was restarted at same time"),
        String.format("Rolling restart failed for domain %s in namespace %s", domainUid, domainNamespace));

    previousTestSuccessful = true;

  }

  /**
   * Add domain scope serverPod env property on the domain resource.
   * Verify all pods are restarted and back to ready state.
   * The tested resource: podSecurityContext: runAsUser: 1000.
   */
  @Test
  @Order(2)
  @DisplayName("Verify server pods are restarted by adding serverPod podSecurityContext")
  @Slow
  public void testServerPodsRestartByAddingPodSecurityContext() {
    Assumptions.assumeTrue(previousTestSuccessful);
    previousTestSuccessful = false;
    // get the original domain resource before update
    Domain domain1 = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace),
        String.format("getDomainCustomResource failed with ApiException when tried to get domain %s in namespace %s",
            domainUid, domainNamespace));
    assertNotNull(domain1, domain1 + " is null");
    assertNotNull(domain1.getSpec(), domain1 + " /spec/serverPod is null");
    assertNotNull(domain1.getSpec().getServerPod(), domain1 + " /spec/serverPod is null");
    assertNotNull(domain1.getSpec().getServerPod().getPodSecurityContext(), domain1
        + "/spec/serverPod/podSecurityContext is null");

    // get the map with server pods and their original creation timestamps
    podsWithTimeStamps = getPodsWithTimeStamps();

    //print out the original podSecurityContext
    logger.info("In the domain1 podSecurityContext is: " + domain1.getSpec().getServerPod().getPodSecurityContext());
    if (domain1.getSpec().getServerPod().getPodSecurityContext() != null) {
      logger.info("In the original domain1 runAsUser is: {0}: ",
          domain1.getSpec().getServerPod().getPodSecurityContext().getRunAsUser());
    }

    Long runAsUser = 1000L;

    StringBuffer patchStr = null;
    patchStr = new StringBuffer("[{");
    patchStr.append("\"op\": \"add\",")
        .append(" \"path\": \"/spec/serverPod/podSecurityContext/runAsUser\",")
        .append("\"value\": ")
        .append(runAsUser)
        .append("}]");
    logger.info("PatchStr for podSecurityContext {0}", patchStr.toString());

    boolean cmPatched = patchDomainResource(domainUid, domainNamespace, patchStr);
    assertTrue(cmPatched, "patchDomainCustomResource(podSecurityContext) failed");

    // verify the server pods are rolling restarted and back to ready state
    logger.info("Verifying rolling restart occurred for domain {0} in namespace {1}",
        domainUid, domainNamespace);
    assertTrue(assertDoesNotThrow(
        () -> verifyRollingRestartOccurred(podsWithTimeStamps, 1, domainNamespace),
        "More than one pod was restarted at same time"),
        String.format("Rolling restart failed for domain %s in namespace %s", domainUid, domainNamespace));

    try {
      Thread.sleep(2 * 1000);
    } catch (InterruptedException ie) {
      // ignore
    }

    Long runAsUserNew = domain1.getSpec().getServerPod().getPodSecurityContext().getRunAsUser();
    assertNotNull(domain1, domain1 + " is null");
    assertNotNull(domain1.getSpec(), domain1 + " /spec/serverPod is null");
    assertNotNull(domain1.getSpec().getServerPod(), domain1 + " /spec/serverPod is null");
    assertNotNull(domain1.getSpec().getServerPod().getPodSecurityContext(), domain1
        + "/spec/serverPod/podSecurityContext is null");
    assertNotNull(runAsUserNew, domain1 + "/spec/serverPod/podSecurityContext/runAsUser is null");

    //verify the runAsUser in the new patched domain
    logger.info("In the new patched domain podSecurityContext is: {0}",
        domain1.getSpec().getServerPod().getPodSecurityContext());
    logger.info("In the new patched domain runAsUser is: {0}", runAsUserNew);
    assertEquals(runAsUserNew.compareTo(runAsUser), 0,
        String.format("podSecurityContext runAsUser was not updated correctly, set runAsUser to %s, got %s",
            runAsUser, runAsUserNew));

    previousTestSuccessful = true;

  }


  /**
   * Modify the domain scope property on the domain resource.
   * Verify all pods are restarted and back to ready state.
   * The resources tested: imagePullPolicy: IfNotPresent --> imagePullPolicy: Never.
   */
  //@Test
  @Order(4)
  @DisplayName("Verify server pods are restarted by changing imagePullPolicy")
  @Slow
  public void testServerPodsRestartByChangingImagePullPolicy() {
    Assumptions.assumeTrue(previousTestSuccessful);
    previousTestSuccessful = false;
    // get the original domain resource before update
    Domain domain1 = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace),
        String.format("getDomainCustomResource failed with ApiException when tried to get domain %s in namespace %s",
            domainUid, domainNamespace));
    assertNotNull(domain1, domain1 + " is null");
    assertNotNull(domain1.getSpec(), domain1 + "/spec is null");

    // get the map with server pods and their original creation timestamps
    podsWithTimeStamps = getPodsWithTimeStamps();

    //print out the original imagePullPolicy
    String imagePullPolicy = domain1.getSpec().getImagePullPolicy();
    logger.info("Original domain imagePullPolicy is: {0}", imagePullPolicy);

    //change imagePullPolicy: IfNotPresent --> imagePullPolicy: Never
    StringBuffer patchStr = null;
    patchStr = new StringBuffer("[{");
    patchStr.append("\"op\": \"replace\",")
        .append(" \"path\": \"/spec/imagePullPolicy\",")
        .append("\"value\": \"")
        .append("Never")
        .append("\"}]");
    logger.info("PatchStr for imagePullPolicy: {0}", patchStr.toString());

    boolean cmPatched = patchDomainResource(domainUid, domainNamespace, patchStr);
    assertTrue(cmPatched, "patchDomainCustomResource(imagePullPolicy) failed");

    domain1 = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace),
        String.format("getDomainCustomResource failed with ApiException when tried to get domain %s in namespace %s",
            domainUid, domainNamespace));
    assertNotNull(domain1, domain1 + " is null");
    assertNotNull(domain1.getSpec(), domain1 + "/spec is null");

    //print out imagePullPolicy in the new patched domain
    imagePullPolicy = domain1.getSpec().getImagePullPolicy();
    logger.info("In the new patched domain imagePullPolicy is: {0}", imagePullPolicy);
    assertTrue(imagePullPolicy.equalsIgnoreCase("Never"), "imagePullPolicy was not updated"
        + " in the new patched domain");

    // verify the server pods are rolling restarted and back to ready state
    logger.info("Verifying rolling restart occurred for domain {0} in namespace {1}",
        domainUid, domainNamespace);
    assertTrue(assertDoesNotThrow(
        () -> verifyRollingRestartOccurred(podsWithTimeStamps, 1, domainNamespace),
        "More than one pod was restarted at same time"),
        String.format("Rolling restart failed for domain %s in namespace %s", domainUid, domainNamespace));
    previousTestSuccessful = true;
  }

  // This method is needed in this test class, since the cleanup util
  // won't cleanup the images.
  //@AfterAll
  void tearDown() {

    // Delete domain custom resource
    logger.info("Delete domain custom resource in namespace {0}", domainNamespace);
    assertDoesNotThrow(() -> deleteDomainCustomResource(domainUid, domainNamespace),
        "deleteDomainCustomResource failed with ApiException");
    logger.info("Deleted Domain Custom Resource " + domainUid + " from " + domainNamespace);

  }

  private void createDomainResource(String domainUid, String domNamespace, String adminSecretName,
                                    String repoSecretName, int replicaCount) {
    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("Image")
            .image(WDT_BASIC_IMAGE_NAME + ":" + WDT_BASIC_IMAGE_TAG)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(repoSecretName))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domNamespace))
            .includeServerOutInPodLog(true)
            .imagePullPolicy("IfNotPresent")
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom "))
                .podSecurityContext(new V1PodSecurityContext()))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                .adminService(new AdminService()
                    .addChannelsItem(new Channel()
                        .channelName("default")
                        .nodePort(0))))
            .addClustersItem(new Cluster()
                .clusterName("cluster-1")
                .replicas(replicaCount)
                .serverStartState("RUNNING"))
            .configuration(new Configuration()
                .model(new Model()
                    .domainType("WLS"))
                .introspectorJobActiveDeadlineSeconds(300L)));

    logger.info("Create domain custom resource for domainUid {0} in namespace {1}",
        domainUid, domNamespace);
    boolean domCreated = assertDoesNotThrow(() -> createDomainCustomResource(domain),
        String.format("Create domain custom resource failed with ApiException for %s in namespace %s",
            domainUid, domNamespace));
    assertTrue(domCreated, String.format("Create domain custom resource failed with ApiException "
        + "for %s in namespace %s", domainUid, domNamespace));
  }

  private Map getPodsWithTimeStamps() {

    // create the map with server pods and their original creation timestamps
    podsWithTimeStamps = new LinkedHashMap<>();
    podsWithTimeStamps.put(adminServerPodName,
        assertDoesNotThrow(() -> getPodCreationTimestamp(domainNamespace, "", adminServerPodName),
            String.format("getPodCreationTimestamp failed with ApiException for pod %s in namespace %s",
                adminServerPodName, domainNamespace)));

    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = managedServerPrefix + i;
      podsWithTimeStamps.put(managedServerPodName,
          assertDoesNotThrow(() -> getPodCreationTimestamp(domainNamespace, "", managedServerPodName),
              String.format("getPodCreationTimestamp failed with ApiException for pod %s in namespace %s",
                  managedServerPodName, domainNamespace)));
    }
    return podsWithTimeStamps;
  }
}
