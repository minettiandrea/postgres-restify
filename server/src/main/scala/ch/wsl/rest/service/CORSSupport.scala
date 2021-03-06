package ch.wsl.rest.service

//https://gist.github.com/joseraya/176821d856b43b1cfe19

import spray.http.{HttpMethods, HttpMethod, HttpResponse, AllOrigins}
import spray.http.HttpHeaders._
import spray.http.HttpMethods._
import spray.routing._
 
// see also https://developer.mozilla.org/en-US/docs/Web/HTTP/Access_control_CORS
trait CORSSupport {
  this: HttpService =>
  
  private val optionsCorsHeaders = List(
    `Access-Control-Allow-Origin`(AllOrigins),
    `Access-Control-Allow-Methods`(OPTIONS,GET,PUT,POST),
    `Access-Control-Allow-Headers`("Origin, X-Requested-With, Content-Type, Accept, Accept-Encoding, Accept-Language, Host, Referer, User-Agent, Authorization"),
    //`Access-Control-Allow-Headers`("Origin, X-Requested-With, Content-Type, Accept, Accept-Encoding, Accept-Language, Host, Referer, User-Agent"),
    `Access-Control-Max-Age`(1728000))
 
  def cors[T]: Directive0 = mapRequestContext { ctx => ctx.withRouteResponseHandling({
    //It is an option requeset for a resource that responds to some other method
    case Rejected(x) if (ctx.request.method.equals(HttpMethods.OPTIONS) && !x.filter(_.isInstanceOf[MethodRejection]).isEmpty) => {
      ctx.complete(HttpResponse().withHeaders(
         optionsCorsHeaders
      ))
    }
  }).withHttpResponseHeadersMapped { headers =>
    optionsCorsHeaders ++ headers
 
  }
  }
}