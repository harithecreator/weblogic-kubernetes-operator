---
title: "Istio support"
date: 2019-08-15T13:30:04-04:00
weight: 1
---

#### Overview

WebLogic Server Kubernetes Operator version 2.3 and later includes support for Istio 1.2.2 and later.
This support allows you to run the operator itself, and WebLogic domains managed by
the operator, with Istio sidecar injection enabled.  It will allow you to use
Istio gateways and virtual services to access applications deployed in these domains.
If your applications have suitable tracing code in them, then you will also be able to
use distributed tracing, such as Jaeger, to trace requests across domains and to
other components and services that have tracing enabled.

You can learn more about Istio at [Istio](https://istio.io/latest/docs/concepts/what-is-istio/)

#### Limitations

The current support for Istio has these limitations:

* It is tested with Istio 1.2.2 and later (up to 1.5.4), however it is tested with both single and
  multicluster installations of Istio.
* You cannot expose the default channel, any attempt will result in an error when deploying the domain.  
* In order to use WLST command, you can define a separate channel in your WebLogic Domain and the expose as a `NodePort` 
in your domain resource YAML instead of accessing the channel through the Istio mesh network.  

#### Using the operator with Istio support

{{% notice note %}}
These instructions assume that you are using a Kubernetes cluster with
[Istio Installation](https://istio.io/latest/docs/setup/install/) installed and configured already.  The operator will not install
Istio for you.
{{% /notice %}}

You can deploy the operator into a namespace which has Istio automatic sidecar
injection enabled.  Before installing the operator, create the namespace you
wish to run the operator in, and label it for automatic injection.

```
$ kubectl create namespace weblogic-operator
$ kubectl label namespace weblogic-operator istio-injection=enabled
```

After the namespace is labeled, you can install the operator using the normal
method.  When the operator pod starts, you will notice that Istio automatically
injects an `initContainer` called `istio-init` and the envoy container `istio-proxy`.

You can check this using the following commands:

```
$ kubectl --namespace weblogic-operator get pods
$ kubectl --namespace weblogic-operator get pod weblogic-operator-xxx-xxx -o yaml
```

In the second command, change `weblogic-operator-xxx-xxx` to the name of your pod.

#### Creating a domain with Istio support

You can configure your domains to run with Istio automatic sidecar injection enabled.
Before creating your domain, create the namespace you wish to run the domain in,
and label it for automatic injection.

```
$ kubectl create namespace domain1
$ kubectl label namespace domain1 istio-injection=enabled
```

To enable the Istio support for a domain, you need to add the
`configuration` section to your domain custom resource YAML file as shown in the
following example:  

```
apiVersion: "weblogic.oracle/v7"
kind: Domain
metadata:
  name: domain2
  namespace: domain1
  labels:
    weblogic.resourceVersion: domain-v2
    weblogic.domainUID: domain2
spec:
  ... other content ...
  configuration:
    istio:
      enabled: true
      readinessPort: 8888
```

To enable the Istio support, you must include the `istio` section
and you must set `enabled: true` as shown.  The `readniessPort` is optional
and defaults to `8888` if not provided, it is used for readiness health check.

##### How Istio-enabled domains differ from regular domains

Istio enforces a number of requirements on pods.  When you enable Istio support in the domain resource, the
introspector job automatically creates configuration overrides with the necessary channels for the domain to satisfy Istio's requirements, including:

When deploying a domain with Istio sidecare injection enabled.  WebLogic Operator automatically add the following network
channels via configuration overrides.

https://istio.io/latest/docs/ops/configuration/traffic-management/protocol-selection/

For non SSL traffic:

|Name|Port|Protocol|Exposed as a container port|
|----|----|--------|-----|
|http-probe|From configuration istio readinessPort|http|N|
|tcp-t3|server listening port|t3|Y|
|http-default|server listening port|http|Y|
|tcp-snmp|server listening port|snmp|Y|
|tcp-cbt|server listening port|CLUSTER-BROADCAST|N|
|tcp-iiop|server listening port|http|N|

For SSL traffic, if SSL is enabled on the server:

|Name|Port|Protocol|Exposed as a container port|
|----|----|--------|-----|
|tls-t3s|server SSL listening port|t3s|Y|
|https-secure|server SSL listening port|https|Y|
|tls-iiops|server SSL listening port|iiops|N|
|tls-ldaps|server SSL listening port|ldaps|N|
|tls-cbts|server listening port|CLUSTER-BROADCAST-SECURE|N|

If WebLogic Administration Port is enabled on the admin server:

|Name|Port|Protocol|Exposed in the container port|
|----|----|--------|-----|
|htps-admin|WebLogic Administration Port|https|Y|


Additionally, when Istio support is enabled for a domain, the operator
ensure that the Istio sidecar is not injected into the introspector job's pods.


### Apply the domain resource yaml

After the domain resource YAML is modified, apply it by

```
kubect apply -f domain.yaml
```

After all the servers are up, you will see output like this:

```
kubectl -n sample-domain1-ns get pods

NAME                             READY   STATUS    RESTARTS   AGE
sample-domain1-admin-server      2/2     Running   0          154m
sample-domain1-managed-server1   2/2     Running   0          153m
sample-domain1-managed-server2   2/2     Running   0          153m

```

If you use `istioctl proxy-status`, you will see the mesh status:

```
istioctl proxy-status 
NAME                                                               CDS        LDS        EDS        RDS          PILOT                            VERSION
istio-ingressgateway-5c7d8d7b5d-tjgtd.istio-system                 SYNCED     SYNCED     SYNCED     NOT SENT     istio-pilot-6cfcdb75dd-87lqm     1.5.4
sample-domain1-admin-server.sample-domain1-ns                      SYNCED     SYNCED     SYNCED     SYNCED       istio-pilot-6cfcdb75dd-87lqm     1.5.4
sample-domain1-managed-server1.sample-domain1-ns                   SYNCED     SYNCED     SYNCED     SYNCED       istio-pilot-6cfcdb75dd-87lqm     1.5.4
sample-domain1-managed-server2.sample-domain1-ns                   SYNCED     SYNCED     SYNCED     SYNCED       istio-pilot-6cfcdb75dd-87lqm     1.5.4
weblogic-operator-7d86fffbdd-5dxzt.sample-weblogic-operator-ns     SYNCED     SYNCED     SYNCED     SYNCED       istio-pilot-6cfcdb75dd-87lqm     1.5.4

```

#### Exposing applications in Istio-enabled domains

When a domain is running with Istio support, you should use the Istio
gateway to provide external access to applications, instead of using an ingress
controller like Traefik.  Using the Istio gateway will enable you to view the
traffic in Kiali and to use distributed tracing all the way from the entry point to
the cluster, for example, the Istio gateway.

To configure external access to your domain, you need to create an Istio `Gateway` and
`Virtualservice` as shown in the example below:

```
---
apiVersion: networking.istio.io/v1alpha3
kind: Gateway
metadata:
  name: domain1-gateway
  namespace: domain1
spec:
  selector:
    istio: ingressgateway
  servers:
    - hosts:
        - '*'
      port:
        name: http
        number: 80
        protocol: HTTP
---
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: domain1-virtualservice
  namespace: domain1
spec:
  gateways:
    - domain1-gateway
  hosts:
    - '*'
  http:
    - match:
        - uri:
            prefix: /console
        - port: 7001
      route:
        - destination:
            host: sample-domain1-admin-server.sample-domain1-ns.svc.cluster.local
            port:
              number: 7001
    - match:
        - uri:
            prefix: /testwebapp
        - port: 8001
      route:
        - destination:
            host: sample-domain1-cluster-cluster-1.domain1.svc.cluster.local
            port:
              number: 8001
```

This example creates a gateway that will accept requests with any host name
using HTTP on port 80, and a virtual service that will route all of
those requests to the cluster service for `cluster-1` in `domain1` in
the namespace `domain1`.

Once the gateway and virtual service has been setup, you can access it through your ingress host and port, 
refer to the section `Determining the ingress IP and ports` in [Istio Getting Started](https://istio.io/latest/docs/setup/getting-started/)


For more information about providing ingress using Istio, refer to the [Istio documentation](https://istio.io/docs/tasks/traffic-management/ingress/).

#### Traffic management

Istio provides traffic management capabilities, including the ability to
visualize traffic in Kiali.  You do not need to change your applications to use
this feature.  The Istio proxy (envoy) sidecar that is injected into your pods
provides this visibility. The Istio support enables
traffic management.  The image below shows an example with traffic
flowing:

* In from the Istio gateway on the left.
* To a domain called `domain1`.

In this example you can see how the traffic flows to the cluster services and
then to the individual Managed Servers.

![Traffic visualization with Kiali](/weblogic-kubernetes-operator/images/kiali.png)

You can learn more about [Istio traffic management](https://istio.io/docs/concepts/traffic-management/)
in their documentation.

#### Distributed tracing

Istio provides distributed tracing capabilities, including the ability to view
traces in Jaeger.  In order to use distributed tracing though, you will need to
instrument your WebLogic application first, for example, using the
[Jaeger Java client](https://github.com/jaegertracing/jaeger-client-java).
The image below shows an example of a distributed trace
that shows a transaction following the same path through the system
as shown in the image above.

![Distributed tracing with Jaeger](/weblogic-kubernetes-operator/images/jaeger.png)

You can learn more about [distrubting tracing in Istio](https://istio.io/docs/tasks/telemetry/distributed-tracing/)
in their documentation.
