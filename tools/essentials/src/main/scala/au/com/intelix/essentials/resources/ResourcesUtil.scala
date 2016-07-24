package au.com.intelix.essentials.resources

import java.io.{File, FileInputStream, InputStream}

import scala.util.{Failure, Success, Try}


object ResourcesUtil {

  def loadResource(name: String): Try[InputStream] = {
    new File(name) match {
      case f if f.exists() && f.isFile && f.canRead => Success(new FileInputStream(f))
      case f if f.exists() => Failure(new Exception(s"Unable to read $name"))
      case _ => try {
          ClassLoader.getSystemClassLoader.getResourceAsStream(name) match {
          case null => Failure(new Exception(s"Unable to locate file or resource $name"))
          case x => Success(x)
        }
      } catch {
        case e: Throwable => Failure(new Exception(s"Unable to locate file or resource $name", e))
      }
    }
  }


}
