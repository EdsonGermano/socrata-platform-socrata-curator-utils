import com.typesafe.tools.mima.plugin.MimaKeys.previousArtifact

com.socrata.cloudbeessbt.SocrataCloudbeesSbt.socrataSettings()

name := "socrata-thirdparty-utils"

previousArtifact <<= scalaBinaryVersion { sv => Some("com.socrata" % ("socrata-thirdparty-utils_" + sv) % "2.0.0") }

libraryDependencies ++= Seq(
  "org.slf4j"          % "slf4j-api"           % "1.7.5",
  "net.sf.opencsv"     % "opencsv"             % "2.3" % "optional",
  "com.typesafe"       % "config"              % "1.0.0" % "optional",
  "com.ning"           % "async-http-client"   % "1.7.13" % "optional",
  "org.apache.curator" % "curator-x-discovery" % "2.4.2" % "provided",
  "org.apache.curator" % "curator-test"        % "2.4.2" % "provided",
  "com.socrata"       %% "socrata-http-client" % "2.0.0" % "optional",
  "org.scalatest"     %% "scalatest"           % "1.9.1" % "test",
  "org.slf4j"          % "slf4j-simple"        % "1.7.5" % "test",
  "com.rojoma"        %% "simple-arm"          % "[1.2.0,2.0.0)",
  "com.rojoma"        %% "rojoma-json"         % "2.4.3" % "optional",
  "com.vividsolutions" % "jts"                 % "1.13" % "provided"
)

scalaVersion := "2.10.0"

testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oFD")
