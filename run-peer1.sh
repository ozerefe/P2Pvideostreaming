#!/bin/bash
# Peer 1'i çalıştır (Maven)
echo "Compiling project..."
mvn -q compile && mvn exec:java -Dexec.args="--udpPort 50000 --tcpPort 50001 --peerName Peer1 --subnetId Subnet-A"
