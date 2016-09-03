#!/bin/bash

export DIR=$1

mkdir -p $DIR

export PW=`cat $DIR/password`

# Create a self signed key pair root CA certificate.
keytool -genkeypair -v \
  -alias serverca \
  -dname "CN=serverCA, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore $DIR/server-ca.jks \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 4096 \
  -ext KeyUsage:critical="keyCertSign" \
  -ext BasicConstraints:critical="ca:true" \
  -validity 9999

# Export the exampleCA public certificate as exampleca.crt so that it can be used in trust stores.
keytool -export -v \
  -alias serverca \
  -file $DIR/server-ca.crt \
  -keypass:env PW \
  -storepass:env PW \
  -keystore $DIR/server-ca.jks \
  -rfc