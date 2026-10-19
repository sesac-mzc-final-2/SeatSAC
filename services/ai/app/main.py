from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.common.errors import BusinessError, error_body
from app.common.trace import TraceIdMiddleware

app = FastAPI(title="seatsac-ai", docs_url=None, redoc_url=None)
app.add_middleware(TraceIdMiddleware)


@app.exception_handler(BusinessError)
async def business_error(request: Request, exc: BusinessError):
    return JSONResponse(status_code=exc.status, content=error_body(exc.code, exc.message, request, exc.details))


@app.get("/actuator/health/liveness")
async def liveness():
    return {"status": "UP"}


@app.get("/actuator/health/readiness")
async def readiness():
    return {"status": "UP"}
