package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.model.headers.{CacheDirectives, `Cache-Control`}

import java.nio.file.Files
import akka.http.scaladsl.server.{Directives, Route}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.{FileUtils, IOUtils}
import pl.touk.nussknacker.engine.version.BuildInfo
import pl.touk.nussknacker.ui.config.{UsageStatisticsReportsConfig, UsageStatisticsUrl}

class WebResources(publicPath: String, usageStatisticsReports: UsageStatisticsReportsConfig) extends Directives with LazyLogging {

  //see config.js comment
  private lazy val mainContentFile = {
    val tempMainContentFile = Files.createTempFile("nussknacker", "main.html").toFile
    tempMainContentFile.deleteOnExit()
    val data = getClass.getClassLoader.getResourceAsStream("web/static/main.html")
    val content = Option(data).map(IOUtils.toString).getOrElse {
      logger.error("Failed to find web/static/main.html - probably frontend resources are not packaged in jar. Frontend won't work properly!")
      ""
    }
    FileUtils.writeStringToFile(tempMainContentFile, withUsageStatisticsReporting(content.replace("__publicPath__", publicPath)))
    tempMainContentFile
  }

  val route: Route = handleAssets("submodules") ~ handleAssets("static") ~ get {
    //main.html instead of index.html to not interfere with flink's static resources...
    getFromFile(mainContentFile) //return UI page by default to make links work
  }

  private def handleAssets(webSubfolder: String) = {
    pathPrefix(webSubfolder) {
      get {
        encodeResponse {
          respondWithHeader(`Cache-Control`(List(CacheDirectives.public, CacheDirectives.`max-age`(0)))) {
            extractRequest { matched =>
              logger.debug(s"Try to get static data from $webSubfolder for:  ${matched.uri.path}.")
              getFromResourceDirectory(s"web/$webSubfolder")
            }
          }
        }
      }
    }
  }

  private def withUsageStatisticsReporting(html: String) = {
    if(usageStatisticsReports.enabled) html.replace("</body>", s"""<img src="${UsageStatisticsUrl(usageStatisticsReports.fingerprint, BuildInfo.version)}" alt="anonymous usage reporting" hidden /></body>""")
    else html
  }

}