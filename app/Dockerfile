FROM python:3.12-slim
WORKDIR /app
COPY server.py .
ENV APP_PORT=8080 PYTHONUNBUFFERED=1
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8080/health',timeout=2).status==200 else 1)"
USER nobody
CMD ["python", "server.py"]
