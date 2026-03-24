#!/usr/bin/env bash
TARGET=${TARGET:-http://localhost:8080}
COUNT=${1:-10}
for i in $(seq 1 $COUNT); do
  curl -s -F "name=A.java" -F "data=@./test/A.java" $TARGET > /dev/null
  curl -s -F "name=B.java" -F "data=@./test/B.java" $TARGET > /dev/null
  echo "Sent pair $i"
done
