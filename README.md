<http://appdev.openshift.io/docs/vertx-runtime.html#mission-secured-vertx>



## Executing the ITs

1. `oc create -f service.sso.yaml`
2. Go in the OpenShift dashboard and wait for readiness
3. `mvn fabric8:deploy -Popenshift`
4. Go in the OpenShift dashboard and wait for readiness
5. `mvn verify -Popenshift-it`
