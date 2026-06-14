-- Delete all demo data: users and listings
-- Clean from most dependent to least dependent tables

-- Delete listing images
DELETE FROM properia.listing_images;

-- Delete all listings
DELETE FROM properia.listings;

-- Delete advertiser team members
DELETE FROM properia.advertiser_team_members;

-- Delete all users
DELETE FROM properia.app_users;

-- Delete all advertisers
DELETE FROM properia.advertisers;
