#!/bin/bash
# 部署 AutoAgent 中转服务到 ddeb
# 用法: ./server/deploy.sh
set -e
HOST=root@your.server.com
DIR=/opt/autoagent-relay

ssh $HOST "mkdir -p $DIR/public"
scp server/relay.js $HOST:$DIR/relay.js
scp server/public/index.html $HOST:$DIR/public/index.html
ssh $HOST "cd $DIR && [ -d node_modules/ws ] || npm install ws --omit=dev"
echo "部署完成。启动: ssh $HOST 'cd $DIR && TOKEN=改成你的token PORT=8787 nohup node relay.js > relay.log 2>&1 &'"
