#!/bin/bash
#
# This wrapper calls the LifePool manifest parser

JAVA=`which java`
if [ -z "${JAVA}" ]; then
        echo "Error: could not find java." >&2
        exit 1
fi

JAR=`dirname $0`/daris-lifepool-parse-1.0-jar-with-dependencies.jar
if [ ! -f "${JAR}" ]; then
        echo "Error: could not find file daris-lifepool-parse-1.0-jar-with-dependencies.jar" >&2
        exit 1
fi


MF_TOKEN=""
# Fillin the authenticationd details for your host
MF_HOST=mediaflux.vicnode.org.au
MF_PORT=443
MF_TRANSPORT=HTTPS
MF_TOKEN=
MF_DOMAIN=
MF_USER=
MF_PASSWORD=

# Configuration [END]:
# =========================
# Do the upload. Hand on all original inputs and ignore -dest
$JAVA -Dmf.host=$MF_HOST -Dmf.port=$MF_PORT -Dmf.transport=$MF_TRANSPORT -Dmf.domain=$MF_DOMAIN -Dmf.user=$MF_USER -Dmf.password=$MF_PASSWORD -Dmf.token=$MF_TOKEN -cp $JAR  vicnode.daris.lifepool.ParseManifest $@
#
RETVAL=$?
exit $RETVA