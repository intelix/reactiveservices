#!/bin/bash


# Create a JKS keystore that trusts the example CA, with the default password.
keytool -import -v \
  -alias clientca \
  -file gen/client-ca.crt \
  -storepass trust_changeme \
  -keystore gen/client-ca-trust.jks << EOF
yes
EOF

# List out the details of the store password.
keytool -list -v \
  -keystore gen/client-ca-trust.jks \
  -storepass trust_changeme

# #context