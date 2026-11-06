"""assessment attempts (server-owned state machine, ADR 0009)

Revision ID: 0004
Revises: 0003
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision = '0004'
down_revision = '0003'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('assessment_attempts',
    sa.Column('id', sa.CHAR(length=36), nullable=False),
    sa.Column('user_id', sa.CHAR(length=36), nullable=False),
    sa.Column('edition_id', sa.String(length=40), nullable=False),
    sa.Column('release_id', sa.BigInteger(), nullable=False),
    sa.Column('assessment_id', sa.String(length=16), nullable=False),
    sa.Column('status', sa.String(length=12), nullable=False),
    sa.Column('seed', sa.BigInteger(), nullable=False),
    sa.Column('layout', sa.JSON(), nullable=False),
    sa.Column('answers', sa.JSON(), nullable=False),
    sa.Column('seq', sa.Integer(), nullable=False),
    sa.Column('started_at', mysql.DATETIME(fsp=6), nullable=False),
    sa.Column('expires_at', mysql.DATETIME(fsp=6), nullable=False),
    sa.Column('submitted_at', mysql.DATETIME(fsp=6), nullable=True),
    sa.Column('submit_request_id', sa.CHAR(length=36), nullable=True),
    sa.Column('auto_submitted', sa.Boolean(), nullable=False),
    sa.Column('score', sa.Integer(), nullable=True),
    sa.Column('max_score', sa.Integer(), nullable=True),
    sa.Column('result', sa.JSON(), nullable=True),
    sa.CheckConstraint("status IN ('in_progress','submitted')", name='ck_attempt_status'),
    sa.ForeignKeyConstraint(['edition_id'], ['editions.id'], ),
    sa.ForeignKeyConstraint(['release_id'], ['content_releases.id'], ),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index('ix_asm_attempts_user', 'assessment_attempts', ['user_id', 'edition_id', 'assessment_id'], unique=False)


def downgrade() -> None:
    op.drop_index('ix_asm_attempts_user', table_name='assessment_attempts')
    op.drop_table('assessment_attempts')
