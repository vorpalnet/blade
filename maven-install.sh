#!/bin/sh


#./maven/com/oracle/weblogic/weblogic-maven-plugin/14.1.1/weblogic-maven-plugin-14.1.1.jar




CURRENT_DIR=$PWD


#works
#cd /Users/jeff/Oracle/weblogic-14.1.1/oracle_common/plugins/maven/com/oracle/maven/oracle-maven-sync/14.1.1
#mvn install:install-file -DpomFile=oracle-maven-sync-14.1.1.pom -Dfile=oracle-maven-sync-14.1.1.jar
#mvn com.oracle.maven:oracle-maven-sync:push -DoracleHome=/Users/jeff/Oracle/weblogic-14.1.1

#works
#cd $WL_HOME/plugins/maven/com/oracle/weblogic/weblogic-maven-plugin/14.1.1/
#mvn install:install-file -DpomFile=weblogic-maven-plugin-14.1.1.pom -Dfile=weblogic-maven-plugin-14.1.1.jar
#mvn com.oracle.weblogic:weblogic-maven-plugin:push -DoracleHome=$MW_HOME




cd $WL_HOME/wlserver/plugins/maven/com/oracle/weblogic/archetype/basic-webservice/14.1.1 
mvn install:install-file -DpomFile=basic-webservice-14.1.1.pom -Dfile=basic-webservice-14.1.1.jar
mvn com.oracle.weblogic.archetype:basic-webservice:push -DoracleHome=$MW_HOME

	





cd $CURRENT_DIR



cd ./oracle_common/plugins
find . -name "*.jar"
cd $CURRENT_DIR





./maven/com/oracle/coherence/archetype/gar-maven-archetype/14.1.1/gar-maven-archetype.14.1.1.jar
./maven/com/oracle/coherence/gar-maven-plugin/14.1.1/gar-maven-plugin.14.1.1.jar
./maven/com/oracle/weblogic/archetype/basic-webapp-ejb/14.1.1/basic-webapp-ejb-14.1.1.jar
./maven/com/oracle/weblogic/archetype/basic-websocket-emulation/14.1.1/basic-websocket-emulation-14.1.1.jar
./maven/com/oracle/weblogic/archetype/basic-mdb/14.1.1/basic-mdb-14.1.1.jar
./maven/com/oracle/weblogic/archetype/basic-webservice/14.1.1/basic-webservice-14.1.1.jar
./maven/com/oracle/weblogic/archetype/basic-websocket/14.1.1/basic-websocket-14.1.1.jar
./maven/com/oracle/weblogic/archetype/basic-webapp/14.1.1/basic-webapp-14.1.1.jar
./maven/com/oracle/weblogic/archetype/basic-serversentevent/14.1.1/basic-serversentevent-14.1.1.jar

./maven/com/oracle/weblogic/weblogic-maven-plugin/14.1.1/weblogic-maven-plugin-14.1.1.jar

./com.oracle.weblogic.lifecycle.plugin.wls.jar
./com.oracle.weblogic.lifecycle.plugin.db.jar
./otd-lifecycle-plugin.jar
./com.oracle.weblogic.lifecycle.plugin.template.jar
./opatchauto/modules/oracle.glcm.opatchauto.core.actions.classpath.jar
./upgrade/WebLogicPlugin.jar
./upgrade/com.oracle.cie.upgrade-plugin_1.5.0.0.jar
jeff@gamera occas-8.1 % 




mvn archetype:generate \
-DarchetypeGroupId=com.oracle.weblogic.archetype \
-DarchetypeArtifactId=basic-webapp \
-DarchetypeVersion=14.1.1-0-0 \
-DgroupId=dave \
-DartifactId=dave-basic-webapp-project \
-Dversion=1.0-SNAPSHOT




.