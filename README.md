<https://appdev.openshift.io/docs/vertx-runtime.html#example-rest-http-secured-vertx>

### Deployment with the JKube OpenShift Maven Plugin

```bash
oc apply -f service.sso.yaml

mvn clean oc:deploy -Popenshift -DSSO_AUTH_SERVER_URL=$(oc get route secure-sso -o jsonpath='https://{.spec.host}/auth')

oc delete -f service.sso.yaml
```

## Executing the ITs

```bash
oc apply -f service.sso.yaml
while ! oc get pods | grep sso| grep -v deploy | grep 1/1; do sleep 1; done
SSO_URL=$(oc get route secure-sso -o jsonpath='https://{.spec.host}/auth')

mvn clean verify -Popenshift,openshift-it -Dskip.sso.init=true -DSSO_AUTH_SERVER_URL=$(oc get route secure-sso -o jsonpath='https://{.spec.host}/auth')
```
