"""exercise attempts (graded checks)

Revision ID: 0003
Revises: 0002
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision = '0003'
down_revision = '0002'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('exercise_attempts',
    sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
    sa.Column('user_id', sa.CHAR(length=36), nullable=False),
    sa.Column('edition_id', sa.String(length=40), nullable=False),
    sa.Column('release_id', sa.BigInteger(), nullable=False),
    sa.Column('exercise_id', sa.String(length=16), nullable=False),
    sa.Column('passed', sa.Boolean(), nullable=False),
    sa.Column('tests_passed', sa.Integer(), nullable=False),
    sa.Column('tests_total', sa.Integer(), nullable=False),
    sa.Column('code_sha256', sa.CHAR(length=64), nullable=False),
    sa.Column('created_at', mysql.DATETIME(fsp=6), nullable=False),
    sa.ForeignKeyConstraint(['edition_id'], ['editions.id'], ),
    sa.ForeignKeyConstraint(['release_id'], ['content_releases.id'], ),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index('ix_attempts_user_exercise', 'exercise_attempts', ['user_id', 'edition_id', 'exercise_id'], unique=False)


def downgrade() -> None:
    op.drop_index('ix_attempts_user_exercise', table_name='exercise_attempts')
    op.drop_table('exercise_attempts')
