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
```

Then build the project:
```sh
mvn package
```

For development, use bear to generate the compile_commands.json file:
```sh
bear -- mvn compile
```

License
-------
MIT, Copyright 2023 SAP SE or an SAP affiliate company, Johannes Bechberger
and trace-tester contributors


*This project is a prototype of the [SapMachine](https://sapmachine.io) team
at [SAP SE](https://sap.com)*