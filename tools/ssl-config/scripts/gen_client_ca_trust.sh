#!/bin/bash


# Create a JKS keystore that trusts the example CA, with the default password.
keytool -import -v \
  -alias clientca \
  -file gen/client-ca.crt \
  -storepass:env PW \
  -keystore gen/client-ca-trust.jks << EOF
yes
EOF

# List out the details of the store password.
keytool -list -v \
  -keystore gen/client-ca-trust.jks \
  -storepass:env PW

# #context