#!/bin/bash
set -e

REPO=/home/ubuntu/data/my-metersphare

echo "=== [1/5] Pull latest code ==="
git -C $REPO pull origin v3.x

echo "=== [2/5] Build backend ==="
cd $REPO
./mvnw clean install -DskipTests -DskipAntRunForJenkins --file backend/pom.xml

echo "=== [3/5] Build frontend (production mode) ==="
export NVM_DIR=$HOME/.nvm
source $NVM_DIR/nvm.sh
nvm use 18
cd $REPO/frontend
rm -rf dist
NODE_OPTIONS=--max-old-space-size=8192 pnpm vite build --config ./config/vite.config.prod.ts
rm -rf public/*
cp -r dist/* public/

echo "=== [4/5] Build Docker image ==="
cd $REPO
rm -rf backend/app/target/dependency
mkdir -p backend/app/target/dependency backend/app/src/main/resources/static
cd backend/app/target/dependency && jar -xf ../app-3.x.jar && cd $REPO
docker build --no-cache -t my-metersphere:latest .

echo "=== [5/5] Restart container ==="
docker compose stop metersphere
docker compose up -d metersphere

echo "=== Done! ==="
