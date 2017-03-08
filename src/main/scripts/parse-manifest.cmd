@echo off

:: This wrapper calls the LifePool manifest parser

set MF_HOST=mediaflux.vicnode.org.au
set MF_PORT=443
set MF_TRANSPORT=HTTPS
:: secure identity token
set MF_TOKEN=XXXXXXXXXXXXXXXXXX
set CID=1128.1.3
set JAR=%~dp0daris-lifepool-parse-1.0-jar-with-dependencies.jar

:: check if the jar file exists
if not exist %JAR% (
    echo "Error: could not find %JAR%"
    exit 1
)

:: execute the jar
java -Dmf.host=%MF_HOST% -Dmf.port=%MF_PORT% -Dmf.transport=%MF_TRANSPORT% -Dmf.domain=%MF_DOMAIN% -Dmf.user=%MF_USER% -Dmf.password=%MF_PASSWORD% -Dmf.token=%MF_TOKEN% -cp %JAR%  vicnode.daris.lifepool.ParseManifest -cid %CID% %*
