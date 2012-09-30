package utils

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base64
import java.net.URLEncoder
;

object UrlUtils {


  private val UTF_8 = "UTF-8";

  /**
   * Creates HmacSha1.
   *
   * @param secretKey SecretKey
   * @param data      target data
   * @return HmacSha1
   */
  def doHmacSha1(secretKey: String, data: String): String = {
    val mac: Mac = Mac.getInstance("HmacSHA1");
    val secret: SecretKeySpec = new SecretKeySpec(secretKey.getBytes("ASCII"), "HmacSHA1");
    mac.init(secret);
    val digest: Array[Byte]  = mac.doFinal(data.getBytes(UTF_8));
    new String(Base64.encodeBase64(digest), "ASCII");

  }

  /**
   * Builds query string.
   *
   * @param params key and value pairs.
   * @return query string.
   * @throws KarotzException
   */
  def buildQuery(params: Map[String, String]): String = {

    if (params == null) {
      return "";
    }

    val sortedParamsIt = params.toList.sortBy{_._1}.iterator;

    val builder = new StringBuilder();

    var next = sortedParamsIt.next();

    builder.append(next._1).append("=").append(encodeString(next._2));
    while (sortedParamsIt.hasNext) {
      next = sortedParamsIt.next();
      builder.append("&").append(next._1).append("=").append(encodeString(next._2));
    }

    builder.toString();
  }

  private def encodeString(value: String) = {
    URLEncoder.encode(value, UTF_8);
  }

}
