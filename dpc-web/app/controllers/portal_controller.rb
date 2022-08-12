# frozen_string_literal: true

class PortalController < ApplicationController
  before_action :authenticate_user!

  def show
    @user = current_user
  end

  def sort
    puts params
  end
end
