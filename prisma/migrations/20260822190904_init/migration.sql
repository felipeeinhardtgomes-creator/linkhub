-- CreateEnum
CREATE TYPE "ItemType" AS ENUM ('APP', 'LINK');

-- CreateEnum
CREATE TYPE "ThemeMode" AS ENUM ('LIGHT', 'DARK', 'AUTO');

-- CreateEnum
CREATE TYPE "MobileLayout" AS ENUM ('LIST', 'GRID');

-- CreateTable
CREATE TABLE "categories" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "icon" TEXT,
    "order" INTEGER NOT NULL DEFAULT 0,
    "visible" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "categories_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "hub_items" (
    "id" TEXT NOT NULL,
    "type" "ItemType" NOT NULL DEFAULT 'APP',
    "name" TEXT NOT NULL,
    "description" TEXT,
    "icon" TEXT,
    "image" TEXT,
    "internalUrl" TEXT,
    "externalUrl" TEXT,
    "categoryId" TEXT,
    "order" INTEGER NOT NULL DEFAULT 0,
    "visible" BOOLEAN NOT NULL DEFAULT true,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "cardSize" TEXT,
    "cardColor" TEXT,
    "openInNewTab" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "hub_items_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "header_config" (
    "id" INTEGER NOT NULL DEFAULT 1,
    "hubName" TEXT NOT NULL DEFAULT 'LinkHub',
    "logoUrl" TEXT,
    "iconUrl" TEXT,
    "headerText" TEXT,
    "backgroundImage" TEXT,
    "showLogo" BOOLEAN NOT NULL DEFAULT true,
    "showName" BOOLEAN NOT NULL DEFAULT true,

    CONSTRAINT "header_config_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "search_config" (
    "id" INTEGER NOT NULL DEFAULT 1,
    "showSearch" BOOLEAN NOT NULL DEFAULT true,
    "placeholderText" TEXT NOT NULL DEFAULT 'Pesquisar serviços...',

    CONSTRAINT "search_config_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "toggle_config" (
    "id" INTEGER NOT NULL DEFAULT 1,
    "showToggle" BOOLEAN NOT NULL DEFAULT true,
    "internalLabel" TEXT NOT NULL DEFAULT 'Interno',
    "externalLabel" TEXT NOT NULL DEFAULT 'Externo',
    "internalIcon" TEXT NOT NULL DEFAULT '🏠',
    "externalIcon" TEXT NOT NULL DEFAULT '🌐',

    CONSTRAINT "toggle_config_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "menu_items" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "icon" TEXT,
    "url" TEXT,
    "order" INTEGER NOT NULL DEFAULT 0,
    "visible" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "menu_items_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "theme_config" (
    "id" INTEGER NOT NULL DEFAULT 1,
    "mode" "ThemeMode" NOT NULL DEFAULT 'AUTO',
    "primaryColor" TEXT NOT NULL DEFAULT '#6366f1',
    "accentColor" TEXT NOT NULL DEFAULT '#22d3ee',
    "backgroundColor" TEXT NOT NULL DEFAULT '#0f172a',
    "cardColor" TEXT NOT NULL DEFAULT '#1e293b',
    "textColor" TEXT NOT NULL DEFAULT '#f8fafc',
    "borderColor" TEXT NOT NULL DEFAULT '#334155',
    "cardRadius" INTEGER NOT NULL DEFAULT 12,

    CONSTRAINT "theme_config_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "layout_config" (
    "id" INTEGER NOT NULL DEFAULT 1,
    "desktopColumns" INTEGER NOT NULL DEFAULT 4,
    "tabletColumns" INTEGER NOT NULL DEFAULT 2,
    "mobileLayout" "MobileLayout" NOT NULL DEFAULT 'LIST',
    "spacing" INTEGER NOT NULL DEFAULT 16,
    "iconSize" INTEGER NOT NULL DEFAULT 32,
    "cardSize" TEXT NOT NULL DEFAULT 'medium',
    "cardRadius" INTEGER NOT NULL DEFAULT 12,
    "showDescription" BOOLEAN NOT NULL DEFAULT true,
    "showCategory" BOOLEAN NOT NULL DEFAULT true,

    CONSTRAINT "layout_config_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "home_page_config" (
    "id" INTEGER NOT NULL DEFAULT 1,
    "pageName" TEXT NOT NULL DEFAULT 'LinkHub',
    "title" TEXT,
    "subtitle" TEXT,
    "logoUrl" TEXT,
    "backgroundImage" TEXT,
    "backgroundColor" TEXT,
    "showCategories" BOOLEAN NOT NULL DEFAULT true,
    "showSearch" BOOLEAN NOT NULL DEFAULT true,
    "showToggle" BOOLEAN NOT NULL DEFAULT true,

    CONSTRAINT "home_page_config_pkey" PRIMARY KEY ("id")
);

-- AddForeignKey
ALTER TABLE "hub_items" ADD CONSTRAINT "hub_items_categoryId_fkey" FOREIGN KEY ("categoryId") REFERENCES "categories"("id") ON DELETE SET NULL ON UPDATE CASCADE;
