#!/bin/bash

export PW=`cat password`

# Create a JKS keystore that trusts the example CA, with the default password.
keytool -import -v \
  -alias serverca \
  -file gen/server-ca.crt \
  -keypass:env PW \
  -storepass changeit \
  -keystore gen/server-ca-trust.jks << EOF
yes
EOF

# List out the details of the store password.
keytool -list -v \
  -keystore gen/server-ca-trust.jks \
  -storepass changeit

# #context