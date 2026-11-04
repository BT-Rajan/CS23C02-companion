import os

from alembic import context
from dotenv import load_dotenv
from sqlalchemy import create_engine, pool

from app.models import Base

load_dotenv()
url = os.environ.get("MIGRATION_DATABASE_URL") or os.environ["DATABASE_URL"]
target_metadata = Base.metadata


def run_migrations_online():
    engine = create_engine(url, poolclass=pool.NullPool)
    with engine.connect() as conn:
        context.configure(connection=conn, target_metadata=target_metadata, compare_type=True)
        with context.begin_transaction():
            context.run_migrations()


run_migrations_online()
