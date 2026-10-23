// 3.1.11 CloudFront Function (viewer-request, cloudfront-js-2.0) — SPA 라우팅
//   확장자가 없는 경로(/, /callback, /movies/12 ...)는 React 진입점 /index.html 을 돌려준다.
//   파일 경로(/assets/index-abc123.js, /favicon.svg)는 그대로 S3 에서 가져온다.
//   /api/* 동작에는 붙이지 않는다(ALB 원본). 쿼리 문자열(?code=...&state=...)은 브라우저 주소에 그대로 남는다.
function handler(event) {
  var request = event.request;
  var uri = request.uri;
  var last = uri.substring(uri.lastIndexOf('/') + 1);
  if (last === '' || last.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
