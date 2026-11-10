import asyncio
from app.main import create_app

async def main():
    app = create_app()
    print(f'App created: {app.title}')
    print(f'Number of routes: {len([r for r in app.routes])}')
    for r in app.routes:
        if hasattr(r, 'path'):
            print(f'  - {r.path}')
    # Return the app for uvicorn
    return app

if __name__ == "__main__":
    app = asyncio.run(main())