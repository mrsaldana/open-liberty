-include= ~${workspace}/cnf/resources/bnd/feature.props
symbolicName = io.openliberty.samlWeb2.0-jaxws2.3
singleton=true
visibility = private
IBM-Provision-Capability:\
 osgi.identity; filter:="(&(type=osgi.subsystem.feature)(osgi.identity=com.ibm.websphere.appserver.samlWeb-2.0))", \
 osgi.identity; filter:="(&(type=osgi.subsystem.feature)(&(osgi.identity=com.ibm.websphere.appserver.jaxws-2.3)))"
-features=\
  com.ibm.websphere.appserver.servlet-3.0; ibm.tolerates:="3.1,4.0", \
  com.ibm.websphere.appserver.appSecurity-2.0; ibm.tolerates:=3.0, \
  io.openliberty.samlWeb2.0.internal.opensaml-3.4
-bundles=\
 com.ibm.ws.security.saml.wab.2.0, \
 com.ibm.ws.security.common
kind=noship
edition=full
