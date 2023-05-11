trace-tester
================

This project provides a library to write tests for [JEP 435 (AsyncGetStackTrace)](https://openjdk.java.net/jeps/435)
and AsyncGetCallTrace.

To build it, first make sure you have my asgst OpenJDK fork as your JDK:
```sh
git clone https://github.com/parttimenerd/jdk --branch asgst
cd jdk
bash configure
make images
export JAVA_HOME=$(`pwd`/build/*/images/jdk)
export PATH=$JAVA_HOME/bin:$PATH

# make and copy the WhiteBox JAR
make build-test-lib
cp build/*/support/test/lib/wb.jar ../lib
cd ..
# make the wb.jar available to the trace-tester project
mvn initialize
```

Then build the project:
```sh
mvn compile
```

To run the test suite:
```sh
mvn test
```

For development, use bear to generate the compile_commands.json file:
```sh
bear -- mvn compile
```

License
-------
GPLv2, Copyright 2023 SAP SE or an SAP affiliate company, Johannes Bechberger
and trace-tester contributors


*This project is a prototype of the [SapMachine](https://sapmachine.io) team
at [SAP SE](https://sap.com)*