#cp -r ~/sd/a/c/* /root/combine/src/combine
#echo "copy completed"
./gradlew deploy
mv build/libs/combineunit.jar ~/sd
