"""anomaly re-verification (ADR 0004, K9)

Revision ID: 0005
Revises: 0004
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision = '0005'
down_revision = '0004'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('users', sa.Column('reverify_since', mysql.DATETIME(fsp=6), nullable=True))
    op.add_column('users', sa.Column('support_flag_at', mysql.DATETIME(fsp=6), nullable=True))
    op.add_column('sessions', sa.Column('ip_hash', sa.CHAR(length=16), nullable=True))
    op.add_column('sessions', sa.Column('reverified_at', mysql.DATETIME(fsp=6), nullable=True))
    op.create_index('ix_sessions_user_created', 'sessions', ['user_id', 'created_at'], unique=False)


def downgrade() -> None:
    op.drop_index('ix_sessions_user_created', table_name='sessions')
    op.drop_column('sessions', 'reverified_at')
    op.drop_column('sessions', 'ip_hash')
    op.drop_column('users', 'support_flag_at')
    op.drop_column('users', 'reverify_since')
