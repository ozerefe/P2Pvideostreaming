#!/bin/bash
# Peer 3'ü çalıştır (Maven - Gateway)
echo "Compiling project..."
mvn -q compile && mvn exec:java -Dexec.args="--udpPort 50020 --tcpPort 50021 --peerName Peer3 --subnetId Subnet-A --isGateway true --gatewaySubnets Subnet-B"
