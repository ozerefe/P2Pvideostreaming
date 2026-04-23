#!/bin/bash
# Peer 2'yi çalıştır (Maven)
echo "Compiling project..."
mvn -q compile && mvn exec:java -Dexec.args="--udpPort 50010 --tcpPort 50011 --peerName Peer2"
