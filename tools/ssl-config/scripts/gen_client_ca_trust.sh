#!/bin/bash


export DIR=$1

mkdir -p $DIR

export PW=`cat $DIR/password`


# Create a JKS keystore that trusts the example CA, with the default password.
keytool -import -v \
  -alias clientca \
  -file $DIR/client-ca.crt \
  -storepass:env PW \
  -keystore $DIR/client-ca-trust.jks << EOF
yes
EOF

# List out the details of the store password.
keytool -list -v \
  -keystore $DIR/client-ca-trust.jks \
  -storepass:env PW

# #context